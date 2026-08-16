package com.aireview.user.service;

import com.aireview.common.dto.PageResponse;
import com.aireview.user.dto.MemberImportResult;
import com.aireview.user.dto.MemberPermissionUpdateRequest;
import com.aireview.user.dto.MemberPermissionsDTO;
import com.aireview.user.dto.SystemFeatureDTO;
import com.aireview.user.dto.UserDTO;
import com.aireview.rule.service.RuleLibraryService;
import com.aireview.user.entity.Unit;
import com.aireview.user.entity.User;
import com.aireview.user.repository.UnitMapper;
import com.aireview.user.repository.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 单位与成员管理。
 *
 * <h2>权限边界</h2>
 * 单位管理员（admin）可以操作本单位及下级单位成员，平台管理员（supervisor）跨单位。这个约束不是靠
 * 前端不显示按钮来保证的——每个写操作都在服务端重新核对目标成员的 unit_id，
 * 见 {@link #requireManageable}。
 *
 * <h2>身份证号</h2>
 * 作为成员唯一编码，全平台不重复。明文只在本类内部用于查重与校验，对外一律经
 * {@link IdCardSupport#mask} 脱敏，也不写进日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final UserMapper userMapper;
    private final UnitMapper unitMapper;
    private final UserService userService;
    private final FeaturePermissionService featurePermissionService;
    private final RuleLibraryService ruleLibraryService;
    private final PasswordEncoder passwordEncoder;

    /** 导入成员的统一初始密码。成员首次登录被强制改密后即失效。 */
    @Value("${member.default-password:Aa123456}")
    private String defaultPassword;

    public static final String ROLE_SUPERVISOR = "supervisor";
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    /**
     * 操作者上下文。角色与单位都从库里现查，不取 JWT 里的值：token 签发后管理员可能
     * 被调岗或降权，凭旧 token 就能继续管原单位是不可接受的。
     */
    private record Operator(Long id, String role, Long unitId) {
        boolean isSupervisor() { return ROLE_SUPERVISOR.equals(role); }
        boolean isAdmin() { return ROLE_ADMIN.equals(role); }
    }

    private Operator loadOperator(Long operatorId) {
        User user = userMapper.selectById(operatorId);
        if (user == null) throw new IllegalArgumentException("操作者账号不存在");
        return new Operator(user.getId(), user.getRole(), user.getUnitId());
    }

    // ---------------- 单位 ----------------

    public List<Unit> listUnits(Long operatorId) {
        Operator operator = requireManager(loadOperator(operatorId));
        List<Unit> all = unitMapper.findAllOrdered();
        if (operator.isSupervisor()) return all;
        Set<Long> manageable = new HashSet<>(manageableUnitIds(operator));
        return all.stream().filter(unit -> manageable.contains(unit.getId())).toList();
    }

    public Unit createUnit(Long parentId, String name, String code, String remark, Long operatorId) {
        Operator operator = requireManager(loadOperator(operatorId));
        if (operator.isAdmin() && parentId == null) {
            throw new IllegalArgumentException("单位管理员只能在本单位或下级单位下新建单位");
        }
        if (parentId != null) {
            if (unitMapper.selectById(parentId) == null) throw new IllegalArgumentException("上级单位不存在");
            requireUnitManageable(parentId, operator);
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("单位名称不能为空");
        Unit existing = unitMapper.findByName(trimmed);
        if (existing != null) throw new IllegalArgumentException("单位已存在：" + trimmed);
        Unit unit = new Unit();
        unit.setName(trimmed);
        unit.setParentId(parentId);
        unit.setCode(code == null || code.isBlank() ? null : code.trim());
        unit.setRemark(remark);
        unit.setCreatedAt(LocalDateTime.now());
        unit.setUpdatedAt(LocalDateTime.now());
        unitMapper.insert(unit);
        log.info("Unit created: {}", trimmed);
        return unit;
    }

    public void deleteUnit(Long unitId, Long operatorId) {
        Operator operator = requireManager(loadOperator(operatorId));
        requireUnitManageable(unitId, operator);
        if (operator.isAdmin() && unitId.equals(operator.unitId())) {
            throw new IllegalArgumentException("不能删除自己所属的单位");
        }
        long children = unitMapper.countChildren(unitId);
        if (children > 0) {
            throw new IllegalArgumentException("该单位下还有 " + children + " 个下级单位，请先处理下级单位");
        }
        long members = userMapper.countByUnit(unitId);
        if (members > 0) {
            // 直接删会把成员的 unit_id 置空，变成一批无归属的游离账号，看板统计也会凭空
            // 少一块。要求先清空成员，让操作者明确处置这些人。
            throw new IllegalArgumentException("该单位下还有 " + members + " 名成员，请先移出或删除后再删除单位");
        }
        unitMapper.deleteById(unitId);
        log.info("Unit {} deleted", unitId);
    }

    // ---------------- 成员 ----------------

    /**
     * 分页查询成员。
     *
     * @param unitId 按单位过滤；null 表示操作者可管理的全部范围
     */
    public PageResponse<UserDTO> listMembers(int page, int size, Long unitId, String keyword,
                                             Long operatorId) {
        Operator operator = requireManager(loadOperator(operatorId));
        Long effectiveUnit = unitId;
        List<Long> scope = null;
        if (operator.isAdmin()) {
            scope = manageableUnitIds(operator);
            if (effectiveUnit != null && !scope.contains(effectiveUnit)) {
                throw new IllegalArgumentException("所选单位不在您的管辖范围内");
            }
        }

        LambdaQueryWrapper<User> query = new LambdaQueryWrapper<>();
        if (effectiveUnit != null) {
            query.eq(User::getUnitId, effectiveUnit);
        } else if (scope != null) {
            query.in(User::getUnitId, scope);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            query.and(w -> w.like(User::getName, kw)
                    .or().like(User::getUsername, kw)
                    .or().like(User::getEmail, kw));
        }
        query.orderByDesc(User::getCreatedAt);

        Page<User> result = userMapper.selectPage(new Page<>(page, size), query);
        List<UserDTO> records = result.getRecords().stream().map(userService::toDTO).toList();
        return PageResponse.of(records, result.getTotal(), page, size);
    }

    /** 单条创建成员。校验规则与 Excel 导入完全一致，避免两条入口标准不一。 */
    @Transactional
    public UserDTO createMember(Long unitId, String username, String name, String idCard,
                                String role, Long operatorId) {
        Operator operator = loadOperator(operatorId);
        requireUnitManageable(unitId, operator);
        User user = buildMember(unitId, username, name, idCard, role);
        userMapper.insert(user);
        log.info("Member created: unit={}, username={}, role={}", unitId, username, user.getRole());
        return userService.toDTO(user);
    }

    /** 兼容原“用户管理”能力：平台账号与组织成员仍写入同一 users 表。 */
    @Transactional
    public UserDTO createPlatformAccount(String email, String password, String name, String role,
                                         Long operatorId) {
        Operator operator = loadOperator(operatorId);
        if (!operator.isSupervisor()) throw new IllegalArgumentException("只有平台管理员可以创建平台账号");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("平台账号不能为空");
        if (password == null || password.length() < 6) throw new IllegalArgumentException("初始密码至少 6 位");
        if (ROLE_ADMIN.equals(normalizeRole(role))) {
            throw new IllegalArgumentException("单位管理员必须创建为有组织归属的成员");
        }
        return userService.createUser(email, password, name, ROLE_USER);
    }

    @Transactional
    public void deleteMember(Long memberId, Long operatorId) {
        User target = requireManageable(memberId, loadOperator(operatorId));
        userMapper.deleteById(target.getId());
        log.info("Member {} deleted", memberId);
    }

    /** 兼容旧接口；新页面通过统一权限接口同时保存角色、功能与规则库。 */
    @Transactional
    public void updateMemberRole(Long memberId, String role, Long operatorId) {
        Operator operator = loadOperator(operatorId);
        User target = requireManageable(memberId, operator);
        String normalized = normalizeRole(role);
        if (ROLE_SUPERVISOR.equals(normalized)) throw new IllegalArgumentException("平台管理员角色不可在组织成员中授予");
        target.setRole(normalized);
        userMapper.updateById(target);
        log.info("Member {} role changed to {}", memberId, normalized);
    }

    /** 重置为初始密码，并要求下次登录必须修改。 */
    @Transactional
    public void resetPassword(Long memberId, Long operatorId) {
        User target = requireManageable(memberId, loadOperator(operatorId));
        target.setPasswordHash(passwordEncoder.encode(defaultPassword));
        target.setMustChangePassword(true);
        userMapper.updateById(target);
        log.info("Member {} password reset to default", memberId);
    }

    // ---------------- 统一权限 ----------------

    public List<SystemFeatureDTO> listGrantableFeatures(Long operatorId) {
        Operator operator = requireManager(loadOperator(operatorId));
        if (operator.isSupervisor()) return featurePermissionService.listAllFeatures();
        Set<String> own = new HashSet<>(featurePermissionService.getFeatureCodes(operator.id()));
        return featurePermissionService.listAllFeatures().stream()
                .filter(feature -> own.contains(feature.getCode()))
                .toList();
    }

    public MemberPermissionsDTO getMemberPermissions(Long memberId, Long operatorId) {
        User target = requireManageable(memberId, requireManager(loadOperator(operatorId)));
        MemberPermissionsDTO dto = new MemberPermissionsDTO();
        dto.setUserId(target.getId());
        dto.setRole(target.getRole());
        dto.setFeatureCodes(featurePermissionService.getFeatureCodes(target.getId()));
        dto.setLibraryIds(userService.getAssignedLibraryIds(target.getId()));
        return dto;
    }

    /**
     * 原子保存成员角色、功能与规则库。单位管理员只能向下转授自己已拥有的权限，
     * 平台管理员可分配全部已登记功能和规则库。
     */
    @Transactional
    public void updateMemberPermissions(Long memberId, MemberPermissionUpdateRequest request,
                                        Long operatorId) {
        Operator operator = requireManager(loadOperator(operatorId));
        User target = requireManageable(memberId, operator);
        String normalizedRole = normalizeRole(request == null ? null : request.getRole());
        if (ROLE_SUPERVISOR.equals(normalizedRole)) {
            throw new IllegalArgumentException("平台管理员角色不可通过成员授权下放");
        }
        if (ROLE_ADMIN.equals(normalizedRole) && target.getUnitId() == null) {
            throw new IllegalArgumentException("单位管理员必须归属具体单位");
        }

        List<String> requestedFeatures = request == null || request.getFeatureCodes() == null
                ? List.of() : request.getFeatureCodes();
        Set<String> grantableFeatures = new HashSet<>(operator.isSupervisor()
                ? featurePermissionService.listAllFeatures().stream().map(SystemFeatureDTO::getCode).toList()
                : featurePermissionService.getFeatureCodes(operator.id()));
        if (!grantableFeatures.containsAll(requestedFeatures)) {
            throw new IllegalArgumentException("不能分配超出自己权限范围的功能");
        }
        List<String> effectiveFeatures = new ArrayList<>(requestedFeatures);
        if (!operator.isSupervisor()) {
            featurePermissionService.getFeatureCodes(target.getId()).stream()
                    .filter(code -> !grantableFeatures.contains(code))
                    .forEach(effectiveFeatures::add);
        }

        List<Long> requestedLibraries = request == null || request.getLibraryIds() == null
                ? List.of() : request.getLibraryIds();
        if (!requestedLibraries.isEmpty()
                && !featurePermissionService.includesSharedRuleLibraryFeature(requestedFeatures)) {
            throw new IllegalArgumentException("分配规则库前必须同时分配使用该规则库的审查功能");
        }
        String operatorRole = operator.isSupervisor() ? ROLE_SUPERVISOR : operator.role();
        Set<Long> grantableLibraries = new HashSet<>(
                ruleLibraryService.grantableLibraryIds(operator.id(), operatorRole));
        if (!grantableLibraries.containsAll(requestedLibraries)) {
            throw new IllegalArgumentException("不能分配不存在或超出自己权限范围的规则库");
        }
        List<Long> effectiveLibraries = new ArrayList<>(requestedLibraries);
        if (!operator.isSupervisor()) {
            userService.getAssignedLibraryIds(target.getId()).stream()
                    .filter(id -> !grantableLibraries.contains(id))
                    .forEach(effectiveLibraries::add);
        }

        target.setRole(normalizedRole);
        userMapper.updateById(target);
        featurePermissionService.replaceAssignments(
                target.getId(), effectiveFeatures.stream().distinct().toList());
        userService.assignLibrariesToUser(
                target.getId(), effectiveLibraries.stream().distinct().toList());
        log.info("Permissions updated: member={}, role={}, features={}, libraries={}",
                memberId, normalizedRole, requestedFeatures.size(), requestedLibraries.size());
    }

    // ---------------- Excel 导入 ----------------

    /**
     * 从 Excel 批量导入成员。
     *
     * <p>列顺序：单位 | 姓名 | 身份证号 | 角色（角色列可空，默认普通用户）。首行为表头。
     *
     * <p>逐行独立处理：一行不合格只跳过该行并记录原因，其余照常导入。整批回滚会让操作者
     * 面对一个「几百行里有一行错，全部白导」的局面，而这类名册表里手误几乎必然存在。
     */
    @Transactional
    public MemberImportResult importFromExcel(MultipartFile file, Long operatorId) {
        Operator operator = loadOperator(operatorId);
        MemberImportResult result = new MemberImportResult();
        DataFormatter formatter = new DataFormatter();
        // 同一批次内的重复也要挡住：数据库唯一约束能挡跨批次重复，但同一个文件里
        // 出现两次相同身份证号时，先插入的那条会让后一条报约束冲突，错误信息对用户
        // 不友好，不如在这里直接说清是「文件内重复」。
        Set<String> batchIdCards = new HashSet<>();
        Set<String> batchUnitUsernames = new HashSet<>();

        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) throw new IllegalArgumentException("Excel 中没有工作表");

            for (int rowIdx = 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) continue;

                String unitName = cell(formatter, row, 0);
                String name = cell(formatter, row, 1);
                String idCard = cell(formatter, row, 2);
                String roleText = cell(formatter, row, 3);

                // 整行空白视为表尾留白，跳过且不计入失败。
                if (unitName.isEmpty() && name.isEmpty() && idCard.isEmpty()) continue;

                int excelRow = rowIdx + 1; // Excel 里的行号从 1 开始，且首行是表头
                try {
                    if (unitName.isEmpty()) throw new IllegalArgumentException("单位不能为空");
                    if (name.isEmpty()) throw new IllegalArgumentException("姓名不能为空");

                    Unit unit = resolveOrCreateUnit(unitName, operator);

                    String normalizedId = IdCardSupport.normalize(idCard);
                    if (!batchIdCards.add(normalizedId)) {
                        throw new IllegalArgumentException("同一文件内身份证号重复");
                    }
                    if (!batchUnitUsernames.add(unit.getId() + " " + name)) {
                        throw new IllegalArgumentException("同一文件内该单位存在重名：" + name);
                    }

                    User user = buildMember(unit.getId(), name, name, normalizedId, roleText);
                    userMapper.insert(user);
                    result.addSuccess(excelRow, name, unit.getName());
                } catch (IllegalArgumentException e) {
                    // 失败原因里绝不回显身份证号，只给行号与姓名定位。
                    result.addFailure(excelRow, name, e.getMessage());
                } catch (Exception e) {
                    log.warn("Member import row {} failed", excelRow, e);
                    result.addFailure(excelRow, name, "导入失败：" + e.getMessage());
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to read member import file", e);
            throw new IllegalArgumentException("无法解析 Excel 文件：" + e.getMessage());
        }

        log.info("Member import finished: {} succeeded, {} failed",
                result.getSuccessCount(), result.getFailureCount());
        return result;
    }

    private Unit resolveOrCreateUnit(String unitName, Operator operator) {
        String trimmed = unitName.trim();
        Unit unit = unitMapper.findByName(trimmed);
        if (unit == null) {
            Long parentId = operator.isSupervisor() ? null : operator.unitId();
            unit = createUnit(parentId, trimmed, null, "Excel 导入时自动创建", operator.id());
        }
        requireUnitManageable(unit.getId(), operator);
        return unit;
    }

    // ---------------- 内部 ----------------

    /** 构建成员实体并做全部校验。单条创建与批量导入共用，保证两条入口标准一致。 */
    private User buildMember(Long unitId, String username, String name, String idCard, String role) {
        if (unitId == null) throw new IllegalArgumentException("必须指定所属单位");
        String loginName = username == null ? "" : username.trim();
        if (loginName.isEmpty()) throw new IllegalArgumentException("用户名不能为空");

        String normalizedId = IdCardSupport.normalize(idCard);
        String invalid = IdCardSupport.validate(normalizedId);
        if (invalid != null) throw new IllegalArgumentException(invalid);

        if (userMapper.findByIdCard(normalizedId) != null) {
            throw new IllegalArgumentException("该身份证号已存在对应成员");
        }
        if (userMapper.findByUnitAndUsername(unitId, loginName) != null) {
            throw new IllegalArgumentException("该单位内已存在同名成员「" + loginName
                    + "」，请在名单中为其加序号区分");
        }

        User user = new User();
        user.setUnitId(unitId);
        user.setUsername(loginName);
        user.setName(name == null || name.isBlank() ? loginName : name.trim());
        user.setIdCard(normalizedId);
        String normalizedRole = normalizeRole(role);
        if (ROLE_SUPERVISOR.equals(normalizedRole)) {
            throw new IllegalArgumentException("组织成员不能设置为平台管理员");
        }
        user.setRole(normalizedRole);
        user.setPasswordHash(passwordEncoder.encode(defaultPassword));
        user.setMustChangePassword(true);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    /** 中英文角色写法归一。识别不出的一律按普通用户处理，不猜。 */
    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) return ROLE_USER;
        String r = role.trim().toLowerCase(Locale.ROOT);
        if (r.contains("主管") || r.equals(ROLE_SUPERVISOR)) return ROLE_SUPERVISOR;
        if (r.contains("管理") || r.equals(ROLE_ADMIN)) return ROLE_ADMIN;
        return ROLE_USER;
    }

    /** 目标单位是否在操作者的管辖范围内。 */
    private void requireUnitManageable(Long unitId, Operator operator) {
        if (operator.isSupervisor()) return;
        if (!operator.isAdmin()) {
            throw new IllegalArgumentException("没有成员管理权限");
        }
        if (unitId == null || !manageableUnitIds(operator).contains(unitId)) {
            throw new IllegalArgumentException("只能管理本单位及下级单位的成员");
        }
    }

    private List<Long> manageableUnitIds(Operator operator) {
        if (operator.unitId() == null) throw new IllegalArgumentException("管理员尚未归属任何单位");
        List<Long> ids = unitMapper.findSubtreeIds(operator.unitId());
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("管理员所属单位不存在");
        return ids;
    }

    private Operator requireManager(Operator operator) {
        if (!operator.isSupervisor() && !operator.isAdmin()) {
            throw new IllegalArgumentException("没有成员与权限管理权限");
        }
        return operator;
    }

    /**
     * 取出目标成员并核对操作权限。所有针对单个成员的写操作都必须先过这里——
     * 权限判断依据的是库里成员当前的 unit_id，而不是请求里带的任何字段。
     */
    private User requireManageable(Long memberId, Operator operator) {
        User target = userMapper.selectById(memberId);
        if (target == null) throw new IllegalArgumentException("成员不存在");
        if (target.getId().equals(operator.id())) {
            throw new IllegalArgumentException("不能对自己执行该操作");
        }
        if (ROLE_SUPERVISOR.equals(target.getRole())) {
            throw new IllegalArgumentException("不能操作平台管理员账号");
        }
        requireUnitManageable(target.getUnitId(), operator);
        return target;
    }

    private static String cell(DataFormatter formatter, Row row, int index) {
        Cell cell = row.getCell(index);
        if (cell == null) return "";
        return formatter.formatCellValue(cell).trim();
    }

    /** 导入模板的表头，供前端下载模板时保持一致。 */
    public static List<String> importTemplateHeaders() {
        return List.of("单位", "姓名", "身份证号", "角色（管理员/普通用户，可空）");
    }
}
