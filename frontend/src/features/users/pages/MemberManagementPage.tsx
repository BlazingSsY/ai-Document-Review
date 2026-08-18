import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert, Button, Card, Checkbox, Col, Divider, Empty, Form, Input, message,
  Modal, Popconfirm, Radio, Row, Select, Space, Statistic, Table, Tag,
  Tooltip, Typography, Upload,
} from 'antd';
import {
  ApartmentOutlined, BankOutlined, DeleteOutlined, KeyOutlined, LockOutlined,
  ReloadOutlined, SafetyCertificateOutlined, SettingOutlined,
  TeamOutlined, UploadOutlined, UserAddOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { UploadFile } from 'antd/es/upload/interface';
import {
  createMember, createPlatformAccount, createUnit, deleteMember, getGrantableFeatures,
  getMemberPermissions, getMembers, getUnits, importMembers, resetMemberPassword,
  updateMemberPermissions, type Member, type MemberImportResult, type SystemFeature,
  type Unit,
} from '../api/members';
import { getAllRuleLibraries, type RuleLibrary } from '../../rules/api/rules';
import useAuthStore from '../../auth/store/authStore';
import { PAGE_SIZE } from '../../../shared/utils/constants';
import {
  usesSharedRuleLibraries, reviewFeatureLabels,
} from '../../review/registry/reviewFeatures';

const { Title, Text, Paragraph } = Typography;

const ROLE_LABELS: Record<string, { label: string; color: string }> = {
  supervisor: { label: '平台管理员', color: 'red' },
  admin: { label: '单位管理员', color: 'blue' },
  user: { label: '普通用户', color: 'default' },
};

type AccountType = 'member' | 'platform';

interface UnitOption {
  label: string;
  value: number;
}

function flattenUnitOptions(units: Unit[]): UnitOption[] {
  const ids = new Set(units.map((unit) => unit.id));
  const children = new Map<number | null, Unit[]>();
  units.forEach((unit) => {
    const parent = unit.parentId != null && ids.has(unit.parentId) ? unit.parentId : null;
    children.set(parent, [...(children.get(parent) || []), unit]);
  });
  children.forEach((items) => items.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')));

  const result: UnitOption[] = [];
  const visit = (parentId: number | null, depth: number) => {
    (children.get(parentId) || []).forEach((unit) => {
      result.push({ label: `${depth ? '　'.repeat(depth) + '└ ' : ''}${unit.name}`, value: unit.id });
      visit(unit.id, depth + 1);
    });
  };
  visit(null, 0);
  return result;
}

function MemberManagementPage() {
  const { user } = useAuthStore();
  const isSupervisor = user?.role === 'supervisor';

  const [units, setUnits] = useState<Unit[]>([]);
  const unitOptions = useMemo(() => flattenUnitOptions(units), [units]);
  const [members, setMembers] = useState<Member[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [unitFilter, setUnitFilter] = useState<number | undefined>();
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);

  const [features, setFeatures] = useState<SystemFeature[]>([]);
  const [memberModalOpen, setMemberModalOpen] = useState(false);
  const [unitModalOpen, setUnitModalOpen] = useState(false);
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<MemberImportResult | null>(null);
  const [importFile, setImportFile] = useState<UploadFile | null>(null);

  const [permissionModalOpen, setPermissionModalOpen] = useState(false);
  const [permissionLoading, setPermissionLoading] = useState(false);
  const [permissionSaving, setPermissionSaving] = useState(false);
  const [permissionMember, setPermissionMember] = useState<Member | null>(null);
  const [grantableLibraries, setGrantableLibraries] = useState<RuleLibrary[]>([]);

  const [memberForm] = Form.useForm();
  const [unitForm] = Form.useForm();
  const [permissionForm] = Form.useForm();
  const accountType = (Form.useWatch('accountType', memberForm) || 'member') as AccountType;
  const selectedFeatures = (Form.useWatch('featureCodes', permissionForm) || []) as string[];
  // 只有复用共享规则库的功能才需要在这里勾选规则库；其余功能自带配置。
  const canAssignLibraries = usesSharedRuleLibraries(selectedFeatures);

  const fetchUnits = useCallback(async () => {
    try {
      const res = await getUnits();
      setUnits(res.data.data || []);
    } catch { /* 请求拦截器统一提示 */ }
  }, []);

  const fetchFeatures = useCallback(async () => {
    try {
      const res = await getGrantableFeatures();
      setFeatures(res.data.data || []);
    } catch { /* 请求拦截器统一提示 */ }
  }, []);

  const fetchMembers = useCallback(async () => {
    setLoading(true);
    try {
      const res = await getMembers({
        page, pageSize: PAGE_SIZE, unitId: unitFilter, keyword: keyword.trim() || undefined,
      });
      const data = res.data.data;
      setMembers(data.records || []);
      setTotal(data.total || 0);
    } catch { /* 请求拦截器统一提示 */ }
    finally { setLoading(false); }
  }, [page, unitFilter, keyword]);

  useEffect(() => { void fetchUnits(); void fetchFeatures(); }, [fetchUnits, fetchFeatures]);
  useEffect(() => { void fetchMembers(); }, [fetchMembers]);

  const openCreateMember = () => {
    memberForm.resetFields();
    memberForm.setFieldsValue({
      accountType: 'member',
      role: 'user',
      unitId: isSupervisor ? undefined : user?.unitId,
    });
    setMemberModalOpen(true);
  };

  const handleCreateMember = async () => {
    try {
      const values = await memberForm.validateFields();
      if (values.accountType === 'platform') {
        await createPlatformAccount({
          email: values.email, password: values.password, name: values.name, role: values.role,
        });
        message.success('平台账号已创建，请继续为其配置功能和规则库');
      } else {
        await createMember({
          unitId: values.unitId,
          username: values.username,
          name: values.username,
          idCard: values.idCard,
          role: values.role,
        });
        message.success('组织成员已创建，请继续为其配置功能和规则库');
      }
      setMemberModalOpen(false);
      memberForm.resetFields();
      void fetchMembers();
    } catch (error) {
      if ((error as { errorFields?: unknown }).errorFields) return;
    }
  };

  const openCreateUnit = () => {
    unitForm.resetFields();
    if (!isSupervisor && user?.unitId) unitForm.setFieldsValue({ parentId: user.unitId });
    setUnitModalOpen(true);
  };

  const handleCreateUnit = async () => {
    try {
      const values = await unitForm.validateFields();
      await createUnit(values);
      message.success('单位已创建');
      setUnitModalOpen(false);
      unitForm.resetFields();
      await fetchUnits();
    } catch (error) {
      if ((error as { errorFields?: unknown }).errorFields) return;
    }
  };

  const openPermissions = async (member: Member) => {
    setPermissionMember(member);
    setPermissionModalOpen(true);
    setPermissionLoading(true);
    permissionForm.resetFields();
    try {
      const canDelegateReview = isSupervisor
        || usesSharedRuleLibraries(features.map((item) => item.code));
      const [permissionRes, libraries] = await Promise.all([
        getMemberPermissions(member.id),
        canDelegateReview
          ? getAllRuleLibraries().then((res) => res.data.data || [])
          : Promise.resolve([] as RuleLibrary[]),
      ]);
      const permissions = permissionRes.data.data;
      setGrantableLibraries(libraries);
      const grantableFeatureCodes = new Set(features.map((feature) => feature.code));
      const grantableLibraryIds = new Set(libraries.map((library) => library.id));
      permissionForm.setFieldsValue({
        role: permissions.role,
        featureCodes: isSupervisor
          ? permissions.featureCodes || []
          : (permissions.featureCodes || []).filter((code) => grantableFeatureCodes.has(code)),
        libraryIds: isSupervisor
          ? permissions.libraryIds || []
          : (permissions.libraryIds || []).filter((id) => grantableLibraryIds.has(id)),
      });
    } catch {
      setPermissionModalOpen(false);
    } finally {
      setPermissionLoading(false);
    }
  };

  const handleSavePermissions = async () => {
    if (!permissionMember) return;
    try {
      const values = await permissionForm.validateFields();
      setPermissionSaving(true);
      await updateMemberPermissions(permissionMember.id, {
        role: values.role,
        featureCodes: values.featureCodes || [],
        libraryIds: canAssignLibraries ? values.libraryIds || [] : [],
      });
      message.success('角色、功能和规则库权限已统一保存');
      setPermissionModalOpen(false);
      await fetchMembers();
    } catch (error) {
      if ((error as { errorFields?: unknown }).errorFields) return;
    } finally {
      setPermissionSaving(false);
    }
  };

  const handleImport = async () => {
    if (!importFile?.originFileObj) {
      message.warning('请先选择 Excel 文件');
      return;
    }
    setImporting(true);
    try {
      const res = await importMembers(importFile.originFileObj as File);
      setImportResult(res.data.data);
      void fetchMembers();
      void fetchUnits();
    } catch { /* 请求拦截器统一提示 */ }
    finally { setImporting(false); }
  };

  const closeImport = () => {
    setImportModalOpen(false);
    setImportResult(null);
    setImportFile(null);
  };

  const columns: ColumnsType<Member> = [
    {
      title: '成员 / 账号', key: 'identity', width: 210,
      render: (_, member) => (
        <Space direction="vertical" size={1}>
          <Text strong>{member.name || member.username || member.email || '未命名账号'}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {member.username ? `登录名：${member.username}` : member.email || '—'}
          </Text>
        </Space>
      ),
    },
    {
      title: '组织归属', dataIndex: 'unitName', key: 'unitName', width: 180,
      render: (name?: string) => name
        ? <Space size={6}><ApartmentOutlined style={{ color: '#8c8c8c' }} />{name}</Space>
        : <Tag>平台直属账号</Tag>,
    },
    {
      title: '唯一身份标识', dataIndex: 'idCardMasked', key: 'idCardMasked', width: 190,
      render: (masked?: string) => masked
        ? <Text code>{masked}</Text>
        : <Text type="secondary">平台账号</Text>,
    },
    {
      title: '角色', dataIndex: 'role', key: 'role', width: 125,
      render: (role: string) => {
        const meta = ROLE_LABELS[role] || ROLE_LABELS.user;
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    {
      title: '已分配权限', key: 'permissions', width: 210,
      render: (_, member) => member.role === 'supervisor' ? (
        <Space size={4} wrap>
          <Tag color="red">全部功能</Tag><Tag color="red">全部规则库</Tag>
        </Space>
      ) : (
        <Space direction="vertical" size={3}>
          <Space size={4} wrap>
            {reviewFeatureLabels(member.featureCodes).length > 0
              ? reviewFeatureLabels(member.featureCodes)
                  .map((label) => <Tag color="blue" key={label}>{label}</Tag>)
              : <Tag>未分配功能</Tag>}
          </Space>
          <Text type="secondary" style={{ fontSize: 12 }}>
            规则库 {member.ruleLibraryCount || 0} 个
          </Text>
        </Space>
      ),
    },
    {
      title: '状态', key: 'status', width: 118,
      render: (_, member) => member.mustChangePassword
        ? <Tag color="orange">待改初始密码</Tag>
        : <Tag color="green">已启用</Tag>,
    },
    {
      title: '操作', key: 'actions', width: 250, fixed: 'right',
      render: (_, member) => {
        const protectedAccount = member.role === 'supervisor' || member.id === user?.id;
        if (protectedAccount) return <Text type="secondary">受保护账号</Text>;
        return (
          <Space size={4}>
            <Button
              size="small"
              type="primary"
              ghost
              icon={<SettingOutlined />}
              onClick={() => { void openPermissions(member); }}
            >
              权限配置
            </Button>
            <Tooltip title="重置为系统初始密码">
              <Popconfirm
                title="重置为初始密码？"
                description="该成员下次登录时必须修改密码。"
                onConfirm={async () => {
                  try {
                    await resetMemberPassword(member.id);
                    message.success('密码已重置');
                    void fetchMembers();
                  } catch { /* 请求拦截器统一提示 */ }
                }}
              >
                <Button size="small" icon={<KeyOutlined />} />
              </Popconfirm>
            </Tooltip>
            <Popconfirm
              title="删除该成员？"
              description="账号与权限会一并删除，历史任务按数据库关联策略保留。"
              onConfirm={async () => {
                try {
                  await deleteMember(member.id);
                  message.success('成员已删除');
                  void fetchMembers();
                } catch { /* 请求拦截器统一提示 */ }
              }}
            >
              <Button size="small" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, alignItems: 'flex-start', marginBottom: 18 }}>
        <div>
          <Title level={4} style={{ margin: 0 }}>成员与权限</Title>
          <Text type="secondary">
            {isSupervisor
              ? '统一管理平台账号、组织成员、功能权限与规则库'
              : '管理本单位及下级单位成员，并在自身权限范围内向下授权'}
          </Text>
        </div>
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={() => { void fetchMembers(); void fetchUnits(); }}>刷新</Button>
          <Button icon={<BankOutlined />} onClick={openCreateUnit}>
            {isSupervisor ? '新建单位' : '新建下级单位'}
          </Button>
          <Button icon={<UploadOutlined />} onClick={() => setImportModalOpen(true)}>Excel 导入</Button>
          <Button type="primary" icon={<UserAddOutlined />} onClick={openCreateMember}>新增成员 / 账号</Button>
        </Space>
      </div>

      <Alert
        showIcon
        type={isSupervisor ? 'info' : 'success'}
        style={{ marginBottom: 16 }}
        message={isSupervisor ? '平台管理员拥有最高权限' : '当前按组织树限定管理范围'}
        description={isSupervisor
          ? '可管理全部单位与成员，并向单位管理员和普通用户分配业务功能及规则库。'
          : '可管理本单位及全部下级单位，也可以将下级单位成员设为单位管理员；只能转授自己已拥有的功能和规则库。'}
      />

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={8}>
          <Card size="small"><Statistic title="可管理成员" value={total} prefix={<TeamOutlined />} /></Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small"><Statistic title="可管理单位" value={units.length} prefix={<ApartmentOutlined />} /></Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small">
            <Statistic
              title="当前页待改初始密码"
              value={members.filter((member) => member.mustChangePassword).length}
              suffix={`/ ${members.length}`}
              prefix={<LockOutlined />}
            />
          </Card>
        </Col>
      </Row>

      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
          <Space wrap>
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="全部可管理单位"
              style={{ width: 260 }}
              value={unitFilter}
              onChange={(value) => { setUnitFilter(value); setPage(1); }}
              options={unitOptions}
            />
            <Input.Search
              allowClear
              placeholder="搜索姓名或登录名"
              style={{ width: 240 }}
              onSearch={(value) => { setKeyword(value); setPage(1); }}
            />
          </Space>
          <Text type="secondary">角色、功能和规则库在同一个权限配置窗口内保存</Text>
        </div>

        <Table
          rowKey="id"
          columns={columns}
          dataSource={members}
          loading={loading}
          scroll={{ x: 1390 }}
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            total,
            onChange: setPage,
            showTotal: (count) => `共 ${count} 名成员 / 账号`,
          }}
        />
      </Card>

      <Modal
        title="新增成员 / 账号"
        open={memberModalOpen}
        onOk={handleCreateMember}
        onCancel={() => setMemberModalOpen(false)}
        okText="创建"
        cancelText="取消"
        width={600}
        destroyOnHidden
      >
        <Form form={memberForm} layout="vertical" initialValues={{ accountType: 'member', role: 'user' }}>
          {isSupervisor && (
            <Form.Item name="accountType" label="账号归属">
              <Radio.Group optionType="button" buttonStyle="solid">
                <Radio.Button value="member">组织成员</Radio.Button>
                <Radio.Button value="platform">平台直属账号</Radio.Button>
              </Radio.Group>
            </Form.Item>
          )}

          {accountType === 'member' ? (
            <>
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 16 }}
                message="成员使用单位 + 登录名登录，初始密码为系统默认密码，首次登录必须修改"
              />
              <Form.Item name="unitId" label="所属单位" rules={[{ required: true, message: '请选择单位' }]}>
                <Select showSearch optionFilterProp="label" placeholder="请选择单位" options={unitOptions} />
              </Form.Item>
              <Form.Item
                name="username"
                label="姓名（同时作为登录名）"
                rules={[{ required: true, message: '请输入姓名' }]}
                extra="登录名在单位内唯一；同名成员可添加序号区分。"
              >
                <Input placeholder="例如：张三" />
              </Form.Item>
              <Form.Item
                name="idCard"
                label="身份证号"
                rules={[{ required: true, message: '请输入身份证号' }, { len: 18, message: '身份证号应为 18 位' }]}
                extra="作为全平台唯一编码，列表中仅显示脱敏值。"
              >
                <Input placeholder="18 位二代身份证号" maxLength={18} />
              </Form.Item>
            </>
          ) : (
            <>
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 16 }}
                message="平台直属账号不属于任何单位，仅平台管理员能够创建和管理"
              />
              <Form.Item name="name" label="显示名称" rules={[{ required: true, message: '请输入显示名称' }]}>
                <Input placeholder="请输入显示名称" />
              </Form.Item>
              <Form.Item name="email" label="登录账号 / 邮箱" rules={[{ required: true, message: '请输入登录账号' }]}>
                <Input placeholder="请输入登录账号或邮箱" />
              </Form.Item>
              <Form.Item
                name="password"
                label="初始密码"
                rules={[{ required: true, message: '请输入初始密码' }, { min: 6, message: '密码至少 6 位' }]}
              >
                <Input.Password placeholder="至少 6 位" />
              </Form.Item>
            </>
          )}

          <Form.Item name="role" label="初始角色">
            <Select options={[
              { label: '普通用户', value: 'user' },
              { label: '单位管理员', value: 'admin', disabled: accountType === 'platform' },
            ]} />
          </Form.Item>
          <Text type="secondary" style={{ fontSize: 12 }}>
            创建完成后，通过列表中的“权限配置”一次性分配功能与规则库。
          </Text>
        </Form>
      </Modal>

      <Modal
        title={permissionMember ? `权限配置 · ${permissionMember.name}` : '权限配置'}
        open={permissionModalOpen}
        onOk={handleSavePermissions}
        onCancel={() => setPermissionModalOpen(false)}
        confirmLoading={permissionSaving}
        okButtonProps={{ disabled: permissionLoading }}
        okText="保存全部权限"
        cancelText="取消"
        width={720}
        destroyOnHidden
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 18 }}
          message={isSupervisor ? '平台级授权' : '组织范围内向下授权'}
          description={isSupervisor
            ? '本次保存会同时更新角色、功能和规则库权限。'
            : '仅显示并允许分配您自己已拥有的功能和规则库；不能越级授权。'}
        />
        {permissionLoading ? (
          <Card loading bordered={false} />
        ) : (
          <Form form={permissionForm} layout="vertical">
            <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 12 }}>
              <SafetyCertificateOutlined style={{ fontSize: 20, color: '#1677ff' }} />
              <div>
                <Text strong>组织角色</Text><br />
                <Text type="secondary" style={{ fontSize: 12 }}>单位管理员可继续管理其本单位及下级单位</Text>
              </div>
            </div>
            <Form.Item name="role" rules={[{ required: true, message: '请选择角色' }]}>
              <Select options={[
                { label: '普通用户', value: 'user' },
                { label: '单位管理员', value: 'admin', disabled: !permissionMember?.unitId },
              ]} />
            </Form.Item>

            <Divider />
            <div style={{ marginBottom: 12 }}>
              <Text strong>功能权限</Text><br />
              <Text type="secondary" style={{ fontSize: 12 }}>决定成员能否进入并调用对应业务功能</Text>
            </div>
            <Form.Item name="featureCodes" style={{ marginBottom: 0 }}>
              <Checkbox.Group style={{ width: '100%' }}>
                <Space direction="vertical" style={{ width: '100%' }}>
                  {features.map((feature) => (
                    <Card key={feature.code} size="small" styles={{ body: { padding: 14 } }}>
                      <Checkbox value={feature.code}>
                        <Space direction="vertical" size={0}>
                          <Text strong>{feature.name}</Text>
                          <Text type="secondary" style={{ fontSize: 12 }}>{feature.description}</Text>
                        </Space>
                      </Checkbox>
                    </Card>
                  ))}
                  {features.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有可向下分配的功能" />}
                </Space>
              </Checkbox.Group>
            </Form.Item>

            <Divider />
            <div style={{ marginBottom: 12 }}>
              <Text strong>规则库权限</Text><br />
              <Text type="secondary" style={{ fontSize: 12 }}>
                只有同时分配“环境试验大纲审查”功能后，规则库才会生效
              </Text>
            </div>
            <Form.Item name="libraryIds" style={{ marginBottom: 0 }}>
              <Checkbox.Group style={{ width: '100%' }} disabled={!canAssignLibraries}>
                <div style={{ maxHeight: 290, overflowY: 'auto', paddingRight: 6 }}>
                  {grantableLibraries.map((library) => (
                    <div key={library.id} style={{ padding: '10px 4px', borderBottom: '1px solid #f0f0f0' }}>
                      <Checkbox value={library.id}>
                        <Space direction="vertical" size={0}>
                          <Text strong>{library.name}</Text>
                          <Text type="secondary" style={{ fontSize: 12 }}>
                            {library.description || '无描述'} · {library.ruleCount} 条规则
                          </Text>
                        </Space>
                      </Checkbox>
                    </div>
                  ))}
                  {grantableLibraries.length === 0 && (
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有可向下分配的规则库" />
                  )}
                </div>
              </Checkbox.Group>
            </Form.Item>
          </Form>
        )}
      </Modal>

      <Modal
        title="新建组织单位"
        open={unitModalOpen}
        onOk={handleCreateUnit}
        onCancel={() => setUnitModalOpen(false)}
        okText="创建"
        cancelText="取消"
        destroyOnHidden
      >
        <Form form={unitForm} layout="vertical">
          <Form.Item
            name="parentId"
            label="上级单位"
            rules={isSupervisor ? [] : [{ required: true, message: '请选择上级单位' }]}
            extra={isSupervisor ? '不选择时创建一级单位。' : '只能在自己可管理的组织范围内创建下级单位。'}
          >
            <Select
              allowClear={isSupervisor}
              showSearch
              optionFilterProp="label"
              placeholder={isSupervisor ? '不选择（创建一级单位）' : '请选择上级单位'}
              options={unitOptions}
            />
          </Form.Item>
          <Form.Item name="name" label="单位名称" rules={[{ required: true, message: '请输入单位名称' }]}>
            <Input placeholder="例如：第一研究所" />
          </Form.Item>
          <Form.Item name="code" label="单位编号（可选）"><Input placeholder="便于与既有台账对齐" /></Form.Item>
          <Form.Item name="remark" label="备注（可选）"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      <Modal
        title="从 Excel 导入成员"
        open={importModalOpen}
        onCancel={closeImport}
        footer={importResult ? (
          <Button type="primary" onClick={closeImport}>完成</Button>
        ) : (
          <Space>
            <Button onClick={closeImport}>取消</Button>
            <Button type="primary" loading={importing} onClick={handleImport}>开始导入</Button>
          </Space>
        )}
        width={680}
        destroyOnHidden
      >
        {!importResult ? (
          <>
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message="表格格式"
              description={(
                <div>
                  <Paragraph style={{ marginBottom: 4 }}>首行为表头，从第二行开始依次填写：</Paragraph>
                  <Text code>单位</Text> <Text code>姓名</Text> <Text code>身份证号</Text>{' '}
                  <Text code>角色（管理员/普通用户，可空）</Text>
                  <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
                    单位管理员导入的新单位会作为本单位的直接下级；已存在单位必须位于可管理范围内。
                    导入完成后仍需在列表中为成员配置功能和规则库。
                  </Paragraph>
                </div>
              )}
            />
            <Upload.Dragger
              maxCount={1}
              accept=".xlsx,.xls"
              beforeUpload={() => false}
              fileList={importFile ? [importFile] : []}
              onChange={(info) => setImportFile(info.fileList[0] ?? null)}
              onRemove={() => setImportFile(null)}
            >
              <p className="ant-upload-drag-icon"><UploadOutlined /></p>
              <p className="ant-upload-text">点击或拖拽 Excel 文件到此处</p>
              <p className="ant-upload-hint">支持 .xlsx / .xls</p>
            </Upload.Dragger>
          </>
        ) : (
          <div>
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col span={12}>
                <Card size="small"><Statistic title="导入成功" value={importResult.successCount} valueStyle={{ color: '#52c41a' }} /></Card>
              </Col>
              <Col span={12}>
                <Card size="small"><Statistic title="失败" value={importResult.failureCount} valueStyle={{ color: importResult.failureCount > 0 ? '#ff4d4f' : undefined }} /></Card>
              </Col>
            </Row>
            {importResult.failureCount > 0 && (
              <>
                <Text strong>失败明细（可据行号修改后重新导入）</Text>
                <Table
                  size="small"
                  rowKey="rowNumber"
                  style={{ marginTop: 8 }}
                  dataSource={importResult.failed}
                  pagination={{ pageSize: 5, size: 'small' }}
                  columns={[
                    { title: '行号', dataIndex: 'rowNumber', width: 70 },
                    { title: '姓名', dataIndex: 'name', width: 100 },
                    { title: '失败原因', dataIndex: 'reason' },
                  ]}
                />
              </>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
}

export default MemberManagementPage;
