import React, { useState, useEffect, useMemo, useRef } from 'react';
import {
  Card, Table, Button, Modal, Form, Input, Space, Typography, Tag,
  message, Popconfirm, Breadcrumb, Empty, Descriptions, Select, Tooltip, Spin, Switch,
  Row, Col,
} from 'antd';
import {
  PlusOutlined, EyeOutlined, DeleteOutlined, FolderOutlined, FolderOpenOutlined,
  ArrowLeftOutlined, FileTextOutlined, EditOutlined, FormOutlined, MinusCircleOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { Rule, RuleLibrary, RuleFolder, RuleUploadConflict } from '../api/rules';
import {
  getRuleApi, PIPELINE_LABEL, PIPELINE_COLOR, type ReviewMode,
} from '../../review/api/pipelineApi';
import RuleUploader from '../components/RuleUploader';
import { PAGE_SIZE } from '../../../shared/utils/constants';
import useAuthStore from '../../auth/store/authStore';

const { Title, Paragraph, Text } = Typography;
const { TextArea } = Input;

const RULE_TYPE_LABELS: Record<string, { label: string; color: string }> = {
  global: { label: '通用', color: 'default' },
  section_specific: { label: '专项', color: 'geekblue' },
  document_specific: { label: '文档级', color: 'purple' },
  test_item_chapter: { label: '试验项目章节', color: 'orange' },
  output: { label: '输出规范', color: 'cyan' },
};

function renderRuleTypeTag(ruleType?: string) {
  if (!ruleType) return <Tag>通用</Tag>;
  const meta = RULE_TYPE_LABELS[ruleType.toLowerCase()];
  return <Tag color={meta?.color || 'default'}>{meta?.label || ruleType}</Tag>;
}

function hasRuleMetadata(rule: Rule): boolean {
  return Boolean(
    rule.ruleCode || rule.ruleType || rule.documentType
      || (rule.sections && rule.sections.length > 0)
      || (rule.keywords && rule.keywords.length > 0)
  );
}

// Fixed column widths for the rule list table. Resizing was removed because
// state-driven resize triggered subtle layout glitches under AntD's fixed-layout
// table; widths chosen here keep all header titles on one line and give the
// frequently-scanned 规则名称 / 适用范围 / 更新时间 columns extra room.
const RULE_COL_WIDTHS = {
  ruleName: 240,
  ruleType: 90,
  scope: 280,
  sourceFile: 160,
  updatedAt: 200,
  action: 160,
} as const;

interface RuleListPageProps {
  /** 当前页所属管线。决定调用 chunk / RAG / SAR 的规则 / 规则库 API。 */
  reviewMode: ReviewMode;
}

function RuleListPage({ reviewMode }: RuleListPageProps) {
  const user = useAuthStore((s) => s.user);
  const canManage = user?.role === 'supervisor' || user?.role === 'admin';

  const ruleApi = useMemo(() => getRuleApi(reviewMode), [reviewMode]);
  const {
    getRuleList, getRuleDetail, uploadRule, importChecklist, updateRuleMetadata, updateRuleContent, deleteRule,
    getRuleLibraryList, createRuleLibrary, deleteRuleLibrary,
    getFolderList, createFolder, updateFolder, deleteFolder,
    getUploadConflicts,
  } = ruleApi;
  const pipelineLabel = PIPELINE_LABEL[reviewMode];
  const pipelineColor = PIPELINE_COLOR[reviewMode];

  // Library state
  const [libraries, setLibraries] = useState<RuleLibrary[]>([]);
  const [libLoading, setLibLoading] = useState(false);
  const [libTotal, setLibTotal] = useState(0);
  const [libPage, setLibPage] = useState(1);
  const [createLibModalOpen, setCreateLibModalOpen] = useState(false);
  const [creatingLib, setCreatingLib] = useState(false);
  const [libForm] = Form.useForm();
  const [selectedLibIds, setSelectedLibIds] = useState<React.Key[]>([]);
  const [batchDeletingLibs, setBatchDeletingLibs] = useState(false);

  // Current library (null = show library list)
  const [currentLibrary, setCurrentLibrary] = useState<RuleLibrary | null>(null);

  // Folder state (within a library)
  const [folders, setFolders] = useState<RuleFolder[]>([]);
  const [folderLoading, setFolderLoading] = useState(false);
  const [createFolderModalOpen, setCreateFolderModalOpen] = useState(false);
  const [savingFolder, setSavingFolder] = useState(false);
  const [editingFolder, setEditingFolder] = useState<RuleFolder | null>(null);
  const [folderForm] = Form.useForm();
  // 当前进入的文件夹（null 且 inUncategorized=false 时停留在文件夹列表）
  const [currentFolder, setCurrentFolder] = useState<RuleFolder | null>(null);
  const [inUncategorized, setInUncategorized] = useState(false);

  // Rules state (within a folder / uncategorized)
  const [rules, setRules] = useState<Rule[]>([]);
  const [ruleLoading, setRuleLoading] = useState(false);
  const [ruleTotal, setRuleTotal] = useState(0);
  const [rulePage, setRulePage] = useState(1);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [previewModalOpen, setPreviewModalOpen] = useState(false);
  const [previewRule, setPreviewRule] = useState<Rule | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const previewRequestId = useRef(0);
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadForm] = Form.useForm();
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingRule, setEditingRule] = useState<Rule | null>(null);
  const [savingEdit, setSavingEdit] = useState(false);
  const [editForm] = Form.useForm();
  // 内容编辑（正文 + 原子检查项）
  const [contentModalOpen, setContentModalOpen] = useState(false);
  const [editingContentRule, setEditingContentRule] = useState<Rule | null>(null);
  const [contentLoading, setContentLoading] = useState(false);
  const [savingContent, setSavingContent] = useState(false);
  const [contentForm] = Form.useForm();
  const [selectedRuleIds, setSelectedRuleIds] = useState<React.Key[]>([]);
  const [batchDeletingRules, setBatchDeletingRules] = useState(false);

  const inRulesView = Boolean(currentLibrary) && (Boolean(currentFolder) || inUncategorized);
  const inFolderView = Boolean(currentLibrary) && !inRulesView;

  // Fetch libraries
  const fetchLibraries = async () => {
    setLibLoading(true);
    try {
      const res = await getRuleLibraryList({ page: libPage, pageSize: PAGE_SIZE });
      setLibraries(res.data.data.records);
      setLibTotal(res.data.data.total);
    } catch { /* handled */ }
    finally { setLibLoading(false); }
  };

  // Fetch folders for current library
  const fetchFolders = async () => {
    if (!currentLibrary) return;
    setFolderLoading(true);
    try {
      const res = await getFolderList(currentLibrary.id);
      setFolders(res.data.data || []);
    } catch { /* handled */ }
    finally { setFolderLoading(false); }
  };

  // Fetch rules for current folder / uncategorized
  const fetchRules = async () => {
    if (!currentLibrary || !inRulesView) return;
    setRuleLoading(true);
    try {
      const res = await getRuleList({
        page: rulePage,
        pageSize: PAGE_SIZE,
        libraryId: currentLibrary.id,
        folderId: currentFolder ? currentFolder.id : undefined,
        uncategorized: inUncategorized || undefined,
      });
      setRules(res.data.data.records);
      setRuleTotal(res.data.data.total);
    } catch { /* handled */ }
    finally { setRuleLoading(false); }
  };

  useEffect(() => {
    if (!currentLibrary) fetchLibraries();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [libPage, currentLibrary]);

  useEffect(() => {
    if (inFolderView) fetchFolders();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentLibrary, currentFolder, inUncategorized]);

  useEffect(() => {
    if (inRulesView) fetchRules();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rulePage, currentFolder, inUncategorized]);

  // 切换管线后清空状态、回到规则库列表，避免显示上一个管线遗留的数据。
  useEffect(() => {
    setCurrentLibrary(null);
    setCurrentFolder(null);
    setInUncategorized(false);
    setLibraries([]);
    setFolders([]);
    setRules([]);
    setSelectedLibIds([]);
    setSelectedRuleIds([]);
    if (libPage !== 1) {
      setLibPage(1);
    } else {
      fetchLibraries();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reviewMode]);

  // Library operations
  const handleCreateLibrary = async (values: { name: string; description: string }) => {
    setCreatingLib(true);
    try {
      await createRuleLibrary(values);
      message.success('规则库创建成功');
      setCreateLibModalOpen(false);
      libForm.resetFields();
      fetchLibraries();
    } catch { /* handled */ }
    finally { setCreatingLib(false); }
  };

  const handleDeleteLibrary = async (id: number) => {
    try {
      await deleteRuleLibrary(id);
      message.success('规则库已删除');
      fetchLibraries();
    } catch { /* handled */ }
  };

  const handleBatchDeleteLibraries = async () => {
    if (selectedLibIds.length === 0) return;
    setBatchDeletingLibs(true);
    let success = 0;
    let failed = 0;
    for (const id of selectedLibIds) {
      try {
        await deleteRuleLibrary(Number(id));
        success += 1;
      } catch {
        failed += 1;
      }
    }
    setBatchDeletingLibs(false);
    setSelectedLibIds([]);
    if (failed === 0) {
      message.success(`已删除 ${success} 个规则库`);
    } else {
      message.warning(`成功 ${success} 个，失败 ${failed} 个`);
    }
    fetchLibraries();
  };

  // Folder operations
  const openCreateFolder = () => {
    setEditingFolder(null);
    folderForm.resetFields();
    setCreateFolderModalOpen(true);
  };

  const openRenameFolder = (folder: RuleFolder) => {
    setEditingFolder(folder);
    folderForm.setFieldsValue({ name: folder.name });
    setCreateFolderModalOpen(true);
  };

  const handleSaveFolder = async (values: { name: string }) => {
    if (!currentLibrary) return;
    setSavingFolder(true);
    try {
      if (editingFolder) {
        await updateFolder(editingFolder.id, { name: values.name });
        message.success('文件夹已重命名');
      } else {
        await createFolder(currentLibrary.id, values.name);
        message.success('文件夹创建成功');
      }
      setCreateFolderModalOpen(false);
      setEditingFolder(null);
      folderForm.resetFields();
      fetchFolders();
    } catch { /* handled */ }
    finally { setSavingFolder(false); }
  };

  const handleToggleFolder = async (folder: RuleFolder, enabled: boolean) => {
    try {
      await updateFolder(folder.id, { enabled });
      message.success(enabled ? `已启用「${folder.name}」，该类规则将参与审查` : `已停用「${folder.name}」，该类规则审查时将被排除`);
      fetchFolders();
    } catch { /* handled */ }
  };

  const handleDeleteFolder = async (id: number) => {
    try {
      await deleteFolder(id);
      message.success('文件夹及其中规则已删除');
      fetchFolders();
    } catch { /* handled */ }
  };

  // Rule operations
  const handleUpload = async () => {
    if (!uploadFile || !currentLibrary) { message.warning('请选择规则文件'); return; }
    const isExcel = /\.(xlsx|xls)$/i.test(uploadFile.name);
    setUploading(true);
    try {
      const shouldReplace = await confirmUploadConflictIfNeeded(uploadFile.name, isExcel);
      if (shouldReplace === null) {
        message.info('已保留已有规则，未上传新文件');
        return;
      }
      const formData = new FormData();
      formData.append('file', uploadFile);
      formData.append('libraryId', String(currentLibrary.id));
      // 进入具体文件夹时上传归入该文件夹；未分类视图不带 folderId（落到未分类）。
      if (currentFolder) formData.append('folderId', String(currentFolder.id));
      if (shouldReplace) formData.append('replaceExisting', 'true');
      if (isExcel) {
        const res = await importChecklist(formData);
        const result = res.data.data;
        message.success(
          `Excel 检查单导入成功，共生成 ${result.ruleCount} 条规则、${result.checkCount} 个检查项`,
        );
      } else {
        const res = await uploadRule(formData);
        const created = res.data?.data ?? [];
        const n = Array.isArray(created) ? created.length : 0;
        message.success(n > 1 ? `规则上传成功，共解析 ${n} 条规则` : '规则上传成功');
      }
      setUploadModalOpen(false);
      setUploadFile(null);
      uploadForm.resetFields();
      fetchRules();
    } catch { /* handled */ }
    finally { setUploading(false); }
  };

  const confirmUploadConflictIfNeeded = async (
    fileName: string,
    checklist: boolean,
  ): Promise<boolean | null> => {
    const res = await getUploadConflicts({
      fileName,
      checklist,
      libraryId: currentLibrary?.id,
      folderId: currentFolder?.id,
    });
    const conflicts = res.data.data || [];
    if (conflicts.length === 0) return false;
    return new Promise((resolve) => {
      Modal.confirm({
        title: '发现同名规则文件',
        width: 620,
        okText: '用新文件替换',
        cancelText: '保留已有',
        okButtonProps: { danger: true },
        content: (
          <div>
            <Paragraph style={{ marginBottom: 8 }}>
              当前位置已存在由同名文件导入的规则。请选择保留已有规则，或删除冲突规则后导入新文件。
            </Paragraph>
            <Card size="small" style={{ background: '#fafafa' }}>
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                {conflicts.map((conflict: RuleUploadConflict) => (
                  <div key={conflict.id}>
                    <Text strong>{conflict.ruleName}</Text>
                    {conflict.ruleCode && <Text type="secondary">（{conflict.ruleCode}）</Text>}
                    <br />
                    <Text type="secondary">来源文件：{conflict.sourceFile}</Text>
                  </div>
                ))}
              </Space>
            </Card>
          </div>
        ),
        onOk: () => resolve(true),
        onCancel: () => resolve(null),
      });
    });
  };

  const openEdit = (rule: Rule) => {
    setEditingRule(rule);
    editForm.setFieldsValue({
      ruleName: rule.ruleName,
      ruleCode: rule.ruleCode || '',
      ruleType: rule.ruleType || 'global',
      sections: rule.sections || [],
      keywords: rule.keywords || [],
      description: rule.description || '',
    });
    setEditModalOpen(true);
  };

  const handleSaveEdit = async (values: Record<string, unknown>) => {
    if (!editingRule) return;
    setSavingEdit(true);
    try {
      const res = await updateRuleMetadata(editingRule.id, {
        ruleName: values.ruleName as string,
        ruleCode: (values.ruleCode as string) || '',
        ruleType: values.ruleType as string,
        sections: (values.sections as string[]) || [],
        keywords: (values.keywords as string[]) || [],
        description: (values.description as string) || '',
      });
      message.success('元信息已保存');
      setRules((prev) => prev.map((rule) => (
        rule.id === editingRule.id ? { ...rule, ...res.data.data } : rule
      )));
      setEditModalOpen(false);
      setEditingRule(null);
      editForm.resetFields();
    } catch { /* handled */ }
    finally { setSavingEdit(false); }
  };

  const openEditContent = async (rule: Rule) => {
    setEditingContentRule(rule);
    setContentModalOpen(true);
    setContentLoading(true);
    try {
      const res = await getRuleDetail(rule.id);
      const full = res.data.data;
      contentForm.setFieldsValue({
        content: full.content || '',
        checks: (full.checks || []).map((c) => ({
          checkCode: c.checkCode,
          checkType: c.checkType || 'presence',
          category: c.category,
          question: c.question,
          passCriteria: c.passCriteria,
          evidenceRequired: c.evidenceRequired ?? true,
        })),
      });
    } catch {
      closeContentModal();
    } finally {
      setContentLoading(false);
    }
  };

  const closeContentModal = () => {
    setContentModalOpen(false);
    setEditingContentRule(null);
    contentForm.resetFields();
  };

  const handleSaveContent = async (values: { content?: string; checks?: Record<string, unknown>[] }) => {
    if (!editingContentRule) return;
    setSavingContent(true);
    try {
      await updateRuleContent(editingContentRule.id, {
        content: values.content ?? '',
        checks: (values.checks || []).map((c, idx) => ({
          checkCode: (c.checkCode as string) || undefined,
          checkType: (c.checkType as string) || 'presence',
          category: (c.category as string) || undefined,
          question: (c.question as string) || '',
          passCriteria: (c.passCriteria as string) || '',
          evidenceRequired: c.evidenceRequired === undefined ? true : Boolean(c.evidenceRequired),
          displayOrder: idx + 1,
        })),
      });
      message.success('规则内容已保存');
      closeContentModal();
      fetchRules();
    } catch { /* handled */ }
    finally { setSavingContent(false); }
  };

  const closePreview = () => {
    previewRequestId.current += 1;
    setPreviewModalOpen(false);
    setPreviewRule(null);
    setPreviewLoading(false);
  };

  const handlePreview = async (rule: Rule) => {
    const requestId = ++previewRequestId.current;
    setPreviewRule(rule);
    setPreviewModalOpen(true);
    setPreviewLoading(true);
    try {
      const res = await getRuleDetail(rule.id);
      if (previewRequestId.current === requestId) {
        setPreviewRule(res.data.data);
      }
    } catch {
      if (previewRequestId.current === requestId) closePreview();
    } finally {
      if (previewRequestId.current === requestId) setPreviewLoading(false);
    }
  };

  const handleDeleteRule = async (id: number) => {
    try {
      await deleteRule(id);
      message.success('规则已删除');
      fetchRules();
    } catch { /* handled */ }
  };

  const handleBatchDeleteRules = async () => {
    if (selectedRuleIds.length === 0) return;
    setBatchDeletingRules(true);
    let success = 0;
    let failed = 0;
    for (const id of selectedRuleIds) {
      try {
        await deleteRule(Number(id));
        success += 1;
      } catch {
        failed += 1;
      }
    }
    setBatchDeletingRules(false);
    setSelectedRuleIds([]);
    if (failed === 0) {
      message.success(`已删除 ${success} 条规则`);
    } else {
      message.warning(`成功 ${success} 条，失败 ${failed} 条`);
    }
    fetchRules();
  };

  const enterLibrary = (lib: RuleLibrary) => {
    closePreview();
    setUploadModalOpen(false);
    setEditModalOpen(false);
    setEditingRule(null);
    setSelectedRuleIds([]);
    setCurrentLibrary(lib);
    setCurrentFolder(null);
    setInUncategorized(false);
    setFolders([]);
    setRules([]);
  };

  const enterFolder = (folder: RuleFolder) => {
    setSelectedRuleIds([]);
    setCurrentFolder(folder);
    setInUncategorized(false);
    setRulePage(1);
    setRules([]);
  };

  const backToLibraries = () => {
    closePreview();
    setUploadModalOpen(false);
    setUploadFile(null);
    setEditModalOpen(false);
    setEditingRule(null);
    setSelectedRuleIds([]);
    setCurrentLibrary(null);
    setCurrentFolder(null);
    setInUncategorized(false);
    setFolders([]);
    setRules([]);
  };

  const backToFolders = () => {
    closePreview();
    setUploadModalOpen(false);
    setUploadFile(null);
    setEditModalOpen(false);
    setEditingRule(null);
    setSelectedRuleIds([]);
    setCurrentFolder(null);
    setInUncategorized(false);
    setRulePage(1);
    setRules([]);
  };

  // Library list columns
  const libColumns: ColumnsType<RuleLibrary> = [
    {
      title: '规则库名称', dataIndex: 'name', key: 'name', width: 200,
      render: (name: string, record) => (
        <a onClick={() => enterLibrary(record)} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <FolderOutlined style={{ color: '#faad14', fontSize: 18 }} />
          <Text strong>{name}</Text>
        </a>
      ),
    },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true, width: 300 },
    {
      title: '规则数量', dataIndex: 'ruleCount', key: 'ruleCount', width: 100,
      render: (count: number) => <Tag color="blue">{count} 条</Tag>,
    },
    {
      title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 180,
      render: (text: string) => text ? new Date(text).toLocaleString('zh-CN') : '-',
    },
    {
      title: '操作', key: 'action', width: 150,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" onClick={() => enterLibrary(record)}>进入</Button>
          {canManage && (
            <Popconfirm title="删除规则库将同时删除其中所有规则，确定吗？"
              onConfirm={() => handleDeleteLibrary(record.id)} okText="确定" cancelText="取消">
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  // Folder list columns
  const folderColumns: ColumnsType<RuleFolder> = [
    {
      title: '文件夹名称', dataIndex: 'name', key: 'name', width: 280,
      render: (name: string, record) => (
        <a onClick={() => enterFolder(record)} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <FolderOpenOutlined style={{ color: record.enabled ? '#faad14' : '#bfbfbf', fontSize: 18 }} />
          <Text strong={record.enabled} type={record.enabled ? undefined : 'secondary'}>{name}</Text>
        </a>
      ),
    },
    {
      title: '规则数量', dataIndex: 'ruleCount', key: 'ruleCount', width: 110,
      render: (count: number) => <Tag color="blue">{count} 条</Tag>,
    },
    {
      title: '是否启用', key: 'enabled', width: 160,
      render: (_, record) => (
        <Space>
          <Switch
            size="small"
            checked={record.enabled}
            disabled={!canManage}
            onChange={(checked) => handleToggleFolder(record, checked)}
          />
          <Text type={record.enabled ? 'success' : 'secondary'}>
            {record.enabled ? '启用' : '停用'}
          </Text>
        </Space>
      ),
    },
    {
      title: '操作', key: 'action', width: 200,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" onClick={() => enterFolder(record)}>进入</Button>
          {canManage && (
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openRenameFolder(record)}>重命名</Button>
          )}
          {canManage && (
            <Popconfirm title="删除文件夹会同时删除其中的全部规则及检查项，且不可恢复。确定删除吗？"
              onConfirm={() => handleDeleteFolder(record.id)} okText="确定" cancelText="取消">
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  // Aggregate every scope keyword in the current view's rule list so the
  // edit modal can offer them as ready-made options.
  const libraryScopeOptions = React.useMemo(() => {
    const set = new Set<string>();
    for (const r of rules) {
      (r.keywords || []).forEach((k) => {
        const trimmed = (k || '').trim();
        if (trimmed) set.add(trimmed);
      });
    }
    return Array.from(set).sort().map((v) => ({ label: v, value: v }));
  }, [rules]);

  // Rule list columns.
  const noWrapHeader: React.HTMLAttributes<HTMLElement> = {
    style: { whiteSpace: 'nowrap' },
  };
  const ruleColumns: ColumnsType<Rule> = [
    {
      title: '规则名称', key: 'ruleName',
      width: RULE_COL_WIDTHS.ruleName,
      onHeaderCell: () => noWrapHeader,
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <Space>
            <FileTextOutlined style={{ color: '#1677ff' }} />
            <Text>{record.ruleName}</Text>
          </Space>
          {record.ruleCode && (
            <Text type="secondary" style={{ fontSize: 11 }}>{record.ruleCode}</Text>
          )}
        </Space>
      ),
    },
    {
      title: '类型', key: 'ruleType',
      width: RULE_COL_WIDTHS.ruleType,
      onHeaderCell: () => noWrapHeader,
      render: (_, record) => renderRuleTypeTag(record.ruleType),
    },
    {
      title: '适用范围', key: 'scope',
      width: RULE_COL_WIDTHS.scope,
      onHeaderCell: () => noWrapHeader,
      render: (_, record) => {
        if (!record.keywords || record.keywords.length === 0) {
          return <Text type="secondary" style={{ fontSize: 12 }}>全文档</Text>;
        }
        return (
          <Space wrap size={4}>
            {record.keywords.map((k) => (
              <Tag key={k} color="blue" style={{ marginInlineEnd: 0 }}>{k}</Tag>
            ))}
          </Space>
        );
      },
    },
    {
      title: '来源文件', dataIndex: 'sourceFile', key: 'sourceFile',
      width: RULE_COL_WIDTHS.sourceFile,
      ellipsis: true,
      onHeaderCell: () => noWrapHeader,
      render: (text?: string) => text ? <Text type="secondary" style={{ fontSize: 12 }}>{text}</Text> : '-',
    },
    {
      title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt',
      width: RULE_COL_WIDTHS.updatedAt,
      onHeaderCell: () => noWrapHeader,
      render: (text: string) => text ? new Date(text).toLocaleString('zh-CN') : '-',
    },
    {
      title: '操作', key: 'action',
      width: RULE_COL_WIDTHS.action,
      onHeaderCell: () => noWrapHeader,
      render: (_, record) => (
        <Space size={4}>
          <Tooltip title="预览">
            <Button type="text" size="small" icon={<EyeOutlined />}
              onClick={() => handlePreview(record)} />
          </Tooltip>
          {canManage && (
            <Tooltip title="编辑元信息">
              <Button type="text" size="small" icon={<EditOutlined />}
                onClick={() => openEdit(record)} />
            </Tooltip>
          )}
          {canManage && (
            <Tooltip title="编辑内容（正文 / 检查项）">
              <Button type="text" size="small" icon={<FormOutlined />}
                onClick={() => openEditContent(record)} />
            </Tooltip>
          )}
          {canManage && (
            <Popconfirm title="确定要删除此规则吗？" onConfirm={() => handleDeleteRule(record.id)}
              okText="确定" cancelText="取消">
              <Tooltip title="删除">
                <Button type="text" size="small" danger icon={<DeleteOutlined />} />
              </Tooltip>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  const ruleTableScrollX = Object.values(RULE_COL_WIDTHS).reduce((a, b) => a + b, 0);

  // 文件夹创建 / 重命名弹窗（两个视图都可能用到，统一在底部渲染）
  const folderModal = (
    <Modal
      title={editingFolder ? '重命名文件夹' : '新建文件夹'}
      open={createFolderModalOpen}
      onCancel={() => { setCreateFolderModalOpen(false); setEditingFolder(null); folderForm.resetFields(); }}
      footer={null}
      destroyOnClose
    >
      <Form form={folderForm} onFinish={handleSaveFolder} layout="vertical">
        <Form.Item name="name" label="文件夹名称" rules={[{ required: true, message: '请输入文件夹名称' }]}
          extra="可按规则类型命名，例如「通用」「磁效应」「霉菌」。">
          <Input placeholder="请输入文件夹名称" />
        </Form.Item>
        <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
          <Space>
            <Button onClick={() => { setCreateFolderModalOpen(false); setEditingFolder(null); }}>取消</Button>
            <Button type="primary" htmlType="submit" loading={savingFolder}>
              {editingFolder ? '保存' : '创建'}
            </Button>
          </Space>
        </Form.Item>
      </Form>
    </Modal>
  );

  // ============ LIBRARY LIST VIEW ============
  if (!currentLibrary) {
    return (
      <div>
        <div className="page-header">
          <Space size={12} align="center">
            <Title level={4} style={{ margin: 0 }}>审查规则管理</Title>
            <Tag color={pipelineColor}>{pipelineLabel}</Tag>
          </Space>
          <Space>
            {canManage && selectedLibIds.length > 0 && (
              <Popconfirm
                title={`确定要删除选中的 ${selectedLibIds.length} 个规则库吗？库中所有规则也将被删除。`}
                onConfirm={handleBatchDeleteLibraries}
                okText="确定"
                cancelText="取消"
              >
                <Button danger icon={<DeleteOutlined />} loading={batchDeletingLibs}>
                  批量删除（{selectedLibIds.length}）
                </Button>
              </Popconfirm>
            )}
            {canManage && (
              <Button type="primary" icon={<PlusOutlined />}
                onClick={() => setCreateLibModalOpen(true)}>新建规则库</Button>
            )}
          </Space>
        </div>

        <Card>
          {libraries.length === 0 && !libLoading ? (
            <Empty description="暂无规则库，请先创建规则库">
              {canManage && (
                <Button type="primary" onClick={() => setCreateLibModalOpen(true)}>新建规则库</Button>
              )}
            </Empty>
          ) : (
            <Table columns={libColumns} dataSource={libraries} rowKey="id" loading={libLoading}
              rowSelection={canManage ? {
                selectedRowKeys: selectedLibIds,
                onChange: (keys) => setSelectedLibIds(keys),
              } : undefined}
              pagination={{
                current: libPage, pageSize: PAGE_SIZE, total: libTotal,
                showTotal: (t) => `共 ${t} 个规则库`,
                onChange: (p) => { setLibPage(p); setSelectedLibIds([]); },
                showSizeChanger: false,
              }}
            />
          )}
        </Card>

        {/* Create Library Modal */}
        <Modal title="新建规则库" open={createLibModalOpen}
          onCancel={() => { setCreateLibModalOpen(false); libForm.resetFields(); }}
          footer={null} destroyOnClose>
          <Form form={libForm} onFinish={handleCreateLibrary} layout="vertical">
            <Form.Item name="name" label="规则库名称" rules={[{ required: true, message: '请输入规则库名称' }]}>
              <Input placeholder="请输入规则库名称" />
            </Form.Item>
            <Form.Item name="description" label="描述">
              <TextArea rows={3} placeholder="请输入规则库描述（可选）" />
            </Form.Item>
            <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
              <Space>
                <Button onClick={() => setCreateLibModalOpen(false)}>取消</Button>
                <Button type="primary" htmlType="submit" loading={creatingLib}>创建</Button>
              </Space>
            </Form.Item>
          </Form>
        </Modal>
      </div>
    );
  }

  // ============ FOLDER LIST VIEW (inside a library) ============
  if (inFolderView) {
    return (
      <div>
        <div className="page-header">
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={backToLibraries}>返回</Button>
            <Breadcrumb items={[
              { title: '审查规则管理', onClick: backToLibraries, className: 'breadcrumb-link' },
              { title: currentLibrary.name },
            ]} />
          </Space>
          <Space>
            {canManage && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreateFolder}>新建文件夹</Button>
            )}
          </Space>
        </div>

        <Card>
          {folders.length === 0 && !folderLoading ? (
            <Empty description="暂无文件夹。可按规则类型（如通用、磁效应、霉菌）新建文件夹归类规则。">
              {canManage && (
                <Button type="primary" onClick={openCreateFolder}>新建文件夹</Button>
              )}
            </Empty>
          ) : (
            <Table columns={folderColumns} dataSource={folders} rowKey="id" loading={folderLoading}
              pagination={false} />
          )}
        </Card>

        {folderModal}
      </div>
    );
  }

  // ============ RULE LIST VIEW (inside a folder / uncategorized) ============
  const folderLabel = inUncategorized ? '未分类规则' : (currentFolder?.name ?? '');
  return (
    <div>
      <div className="page-header">
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={backToFolders}>返回</Button>
          <Breadcrumb items={[
            { title: '审查规则管理', onClick: backToLibraries, className: 'breadcrumb-link' },
            { title: currentLibrary.name, onClick: backToFolders, className: 'breadcrumb-link' },
            { title: folderLabel },
          ]} />
          {!inUncategorized && currentFolder && !currentFolder.enabled && (
            <Tag color="default">已停用（审查时排除）</Tag>
          )}
        </Space>
        <Space>
          {canManage && selectedRuleIds.length > 0 && (
            <Popconfirm
              title={`确定要删除选中的 ${selectedRuleIds.length} 条规则吗？`}
              onConfirm={handleBatchDeleteRules}
              okText="确定"
              cancelText="取消"
            >
              <Button danger icon={<DeleteOutlined />} loading={batchDeletingRules}>
                批量删除（{selectedRuleIds.length}）
              </Button>
            </Popconfirm>
          )}
          {canManage && (
            <Button type="primary" icon={<PlusOutlined />}
              onClick={() => setUploadModalOpen(true)}>上传规则</Button>
          )}
        </Space>
      </div>

      <Card className="rule-table-card">
        {rules.length === 0 && !ruleLoading ? (
          <Empty description={inUncategorized ? '暂无未分类规则' : '该文件夹暂无规则'}>
            {canManage && (
              <Button type="primary" onClick={() => setUploadModalOpen(true)}>上传规则</Button>
            )}
          </Empty>
        ) : (
          <Table
            columns={ruleColumns}
            dataSource={rules}
            rowKey="id"
            loading={ruleLoading}
            size="middle"
            tableLayout="fixed"
            scroll={{ x: ruleTableScrollX + (canManage ? 50 : 0) }}
            rowSelection={canManage ? {
              columnWidth: 50,
              selectedRowKeys: selectedRuleIds,
              onChange: (keys) => setSelectedRuleIds(keys),
            } : undefined}
            pagination={{
              current: rulePage, pageSize: PAGE_SIZE, total: ruleTotal,
              showTotal: (t) => `共 ${t} 条规则`,
              onChange: (p) => { setRulePage(p); setSelectedRuleIds([]); },
              showSizeChanger: false,
            }}
          />
        )}
      </Card>

      {/* Upload Rule Modal */}
      <Modal title={`上传规则到「${folderLabel}」`} open={uploadModalOpen}
        onCancel={() => { setUploadModalOpen(false); setUploadFile(null); uploadForm.resetFields(); }}
        footer={null} destroyOnClose>
        <Form form={uploadForm} onFinish={handleUpload} layout="vertical">
          <Form.Item label="规则文件" required>
            <RuleUploader
              onFileSelect={(file) => setUploadFile(file)}
              onFileRemove={() => setUploadFile(null)}
            />
          </Form.Item>
          {uploadFile && /\.(xlsx|xls)$/i.test(uploadFile.name) && (
            <Text type="secondary">
              Excel 文件将按检查单格式解析，并自动生成规则及原子检查项。
            </Text>
          )}
          <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setUploadModalOpen(false)}>取消</Button>
              <Button type="primary" htmlType="submit" loading={uploading}
                disabled={!uploadFile}>上传</Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* Preview Modal */}
      <Modal title={`规则预览 - ${previewRule?.ruleName || ''}`} open={previewModalOpen}
        onCancel={closePreview}
        footer={<Button onClick={closePreview}>关闭</Button>} width={780} destroyOnClose>
        <Spin spinning={previewLoading}>
        {previewRule && !previewLoading && (
          <div>
            {hasRuleMetadata(previewRule) && (
              <>
                <Title level={5}>规则元信息</Title>
                <Descriptions size="small" bordered column={2} style={{ marginBottom: 16 }}>
                  {previewRule.ruleCode && (
                    <Descriptions.Item label="规则编号">{previewRule.ruleCode}</Descriptions.Item>
                  )}
                  <Descriptions.Item label="规则类型">{renderRuleTypeTag(previewRule.ruleType)}</Descriptions.Item>
                  {previewRule.sections && previewRule.sections.length > 0 && (
                    <Descriptions.Item label="目标章节" span={2}>
                      <Space wrap>
                        {previewRule.sections.map((s) => <Tag key={s}>{s}</Tag>)}
                      </Space>
                    </Descriptions.Item>
                  )}
                  {previewRule.keywords && previewRule.keywords.length > 0 && (
                    <Descriptions.Item label="匹配关键词" span={2}>
                      <Space wrap>
                        {previewRule.keywords.map((k) => <Tag color="blue" key={k}>{k}</Tag>)}
                      </Space>
                    </Descriptions.Item>
                  )}
                </Descriptions>
              </>
            )}
            <Title level={5}>规则内容</Title>
            <Card size="small" style={{ maxHeight: 400, overflow: 'auto', background: '#fafafa', marginBottom: 16 }}>
              <Paragraph>
                <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontSize: 13 }}>
                  {previewRule.content || '暂无内容'}
                </pre>
              </Paragraph>
            </Card>
          </div>
        )}
        </Spin>
      </Modal>

      {/* Edit Metadata Modal */}
      <Modal
        title={`编辑规则元信息 - ${editingRule?.ruleName || ''}`}
        open={editModalOpen}
        onCancel={() => { setEditModalOpen(false); setEditingRule(null); editForm.resetFields(); }}
        footer={null}
        destroyOnClose
        width={680}
      >
        <Form form={editForm} layout="vertical" onFinish={handleSaveEdit}>
          <Form.Item name="ruleName" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如 G-7-temperature_test" />
          </Form.Item>
          <Form.Item name="ruleCode" label="规则编号">
            <Input placeholder="例如 DO160G-13-001（可选）" />
          </Form.Item>
          <Form.Item name="ruleType" label="规则类型" rules={[{ required: true, message: '请选择规则类型' }]}>
            <Select
              options={[
                { label: '通用规则（应用到所有章节）', value: 'global' },
                { label: '专项规则（按章节匹配触发）', value: 'section_specific' },
                { label: '文档级规则（全文综合审查）', value: 'document_specific' },
                { label: '试验项目章节规则（自动作用于试验概述声明的试验项目章节）', value: 'test_item_chapter' },
                { label: '输出规范规则', value: 'output' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="sections"
            label="目标章节"
            extra="标准的章节编号（如 13、15、4.5）。按回车输入多个；仅专项规则会按此匹配文档一级标题"
          >
            <Select mode="tags" tokenSeparators={[',', '，', ' ']} placeholder="回车确认每个章节号" />
          </Form.Item>
          <Form.Item
            name="keywords"
            label="匹配关键词"
            extra="自定义本规则的匹配关键词：审查时会与上传文档的一级标题做匹配，命中任一关键词即认为该规则适用于该章节（仅专项规则按此触发）。例如磁影响章节可同时填“磁影响、磁效应”。下拉可选当前已有关键词，也可直接键入后回车新增。"
          >
            <Select
              mode="tags"
              tokenSeparators={[',', '，']}
              placeholder="选择或输入匹配关键词"
              options={libraryScopeOptions}
            />
          </Form.Item>
          <Form.Item name="description" label="规则简述">
            <Input.TextArea rows={3} placeholder="便于在列表中识别该规则的一句话描述" />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
            <Space>
              <Button onClick={() => { setEditModalOpen(false); setEditingRule(null); }}>取消</Button>
              <Button type="primary" htmlType="submit" loading={savingEdit}>保存</Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* Edit Content Modal (规则正文 + 原子检查项) */}
      <Modal
        title={`编辑规则内容 - ${editingContentRule?.ruleName || ''}`}
        open={contentModalOpen}
        onCancel={closeContentModal}
        footer={null}
        width={880}
        forceRender
      >
        <Spin spinning={contentLoading}>
          <Form form={contentForm} layout="vertical" onFinish={handleSaveContent}>
            <Form.Item
              name="content"
              label="规则正文"
              extra="规则的文本主体（.md/.json 内容）。全文逐章审查直接据此审查；智能召回 / 结构化审查以下方的原子检查项为准。"
            >
              <Input.TextArea rows={8} placeholder="规则正文内容" />
            </Form.Item>

            <Typography.Title level={5} style={{ marginBottom: 0 }}>原子检查项</Typography.Title>
            <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
              每条 = 一个独立是/否判定；智能召回 / 结构化审查按这些检查项逐项判定 通过 / 不通过 / 待复核。
            </Typography.Paragraph>

            <Form.List name="checks">
              {(fields, { add, remove }) => (
                <>
                  {fields.map((field) => (
                    <Card
                      key={field.key}
                      size="small"
                      style={{ marginBottom: 12, background: '#fafafa' }}
                      title={`检查项 ${field.name + 1}`}
                      extra={
                        <Button type="text" size="small" danger icon={<MinusCircleOutlined />}
                          onClick={() => remove(field.name)}>删除</Button>
                      }
                    >
                      <Row gutter={12}>
                        <Col span={8}>
                          <Form.Item name={[field.name, 'checkCode']} label="检查编号" extra="留空自动生成">
                            <Input placeholder="如 G-2-C001" />
                          </Form.Item>
                        </Col>
                        <Col span={8}>
                          <Form.Item name={[field.name, 'checkType']} label="类型" initialValue="presence">
                            <Select options={[
                              { value: 'presence', label: '存在性 presence' },
                              { value: 'consistency', label: '一致性 consistency' },
                              { value: 'numeric', label: '数值 numeric' },
                            ]} />
                          </Form.Item>
                        </Col>
                        <Col span={8}>
                          <Form.Item name={[field.name, 'category']} label="分类">
                            <Select allowClear placeholder="可选" options={
                              ['完整性', '标准符合性', '逻辑一致性', '术语一致性', '格式', '其他']
                                .map((v) => ({ value: v, label: v }))
                            } />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Form.Item name={[field.name, 'question']} label="检查问题"
                        rules={[{ required: true, message: '请输入检查问题' }]}>
                        <Input.TextArea rows={2} placeholder="例如：是否包含『地面低温耐受和低温短时工作』试验项目？" />
                      </Form.Item>
                      <Form.Item name={[field.name, 'passCriteria']} label="通过标准">
                        <Input.TextArea rows={2} placeholder="例如：大纲明确列出该项即通过；缺失则不通过。" />
                      </Form.Item>
                      <Form.Item name={[field.name, 'evidenceRequired']} label="需要证据"
                        valuePropName="checked" initialValue={true} style={{ marginBottom: 0 }}>
                        <Switch />
                      </Form.Item>
                    </Card>
                  ))}
                  <Button type="dashed" block icon={<PlusOutlined />}
                    onClick={() => add({ checkType: 'presence', evidenceRequired: true })}>
                    添加检查项
                  </Button>
                </>
              )}
            </Form.List>

            <Form.Item style={{ marginTop: 16, marginBottom: 0, textAlign: 'right' }}>
              <Space>
                <Button onClick={closeContentModal}>取消</Button>
                <Button type="primary" htmlType="submit" loading={savingContent}>保存</Button>
              </Space>
            </Form.Item>
          </Form>
        </Spin>
      </Modal>

      {folderModal}
    </div>
  );
}

export default RuleListPage;
