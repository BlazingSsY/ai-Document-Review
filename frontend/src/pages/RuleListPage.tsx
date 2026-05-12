import React, { useState, useEffect, useRef } from 'react';
import {
  Card, Table, Button, Modal, Form, Input, Space, Typography, Tag,
  message, Popconfirm, Breadcrumb, Empty, Descriptions, Select, Tooltip,
} from 'antd';
import {
  PlusOutlined, EyeOutlined, DeleteOutlined, FolderOutlined,
  ArrowLeftOutlined, FileTextOutlined, EditOutlined,
} from '@ant-design/icons';
import type { ColumnsType, ColumnType } from 'antd/es/table';
import {
  getRuleList, uploadRule, updateRuleMetadata, deleteRule, Rule, RuleLibrary,
  getRuleLibraryList, createRuleLibrary, deleteRuleLibrary,
} from '../api/rules';
import RuleUploader from '../components/RuleUploader';
import { PAGE_SIZE } from '../utils/constants';
import useAuthStore from '../store/authStore';

const { Title, Paragraph, Text } = Typography;
const { TextArea } = Input;

const RULE_TYPE_LABELS: Record<string, { label: string; color: string }> = {
  global: { label: '通用', color: 'default' },
  section_specific: { label: '专项', color: 'geekblue' },
  document_specific: { label: '文档级', color: 'purple' },
  output: { label: '输出规范', color: 'cyan' },
};

function renderRuleTypeTag(ruleType?: string) {
  if (!ruleType) return <Tag>通用</Tag>;
  const meta = RULE_TYPE_LABELS[ruleType.toLowerCase()];
  return <Tag color={meta?.color || 'default'}>{meta?.label || ruleType}</Tag>;
}

function hasRuleMetadata(rule: Rule): boolean {
  return Boolean(
    rule.ruleCode || rule.ruleType || rule.documentType || rule.standard
      || (rule.sections && rule.sections.length > 0)
      || (rule.keywords && rule.keywords.length > 0)
  );
}

interface ResizableHeaderCellProps extends React.HTMLAttributes<HTMLTableCellElement> {
  width?: number;
  onResize?: (newWidth: number) => void;
}

const ResizableHeaderCell: React.FC<ResizableHeaderCellProps> = ({
  width, onResize, style, children, ...restProps
}) => {
  const startX = useRef(0);
  const startWidth = useRef(0);
  const moveRef = useRef<(e: MouseEvent) => void>();
  const upRef = useRef<() => void>();

  if (!onResize || width === undefined) {
    return <th {...restProps} style={style}>{children}</th>;
  }

  const onMouseDown = (e: React.MouseEvent) => {
    startX.current = e.clientX;
    startWidth.current = width;
    document.body.style.userSelect = 'none';
    moveRef.current = (ev: MouseEvent) => {
      const next = Math.max(60, startWidth.current + (ev.clientX - startX.current));
      onResize(next);
    };
    upRef.current = () => {
      if (moveRef.current) document.removeEventListener('mousemove', moveRef.current);
      if (upRef.current) document.removeEventListener('mouseup', upRef.current);
      document.body.style.userSelect = '';
    };
    document.addEventListener('mousemove', moveRef.current);
    document.addEventListener('mouseup', upRef.current);
    e.preventDefault();
    e.stopPropagation();
  };

  return (
    <th {...restProps} style={{ ...style, position: 'relative' }}>
      {children}
      <span
        onMouseDown={onMouseDown}
        onClick={(e) => e.stopPropagation()}
        style={{
          position: 'absolute',
          right: -4,
          top: 0,
          bottom: 0,
          width: 8,
          cursor: 'col-resize',
          zIndex: 2,
          userSelect: 'none',
        }}
      />
    </th>
  );
};

function RuleListPage() {
  const user = useAuthStore((s) => s.user);
  const canManage = user?.role === 'supervisor' || user?.role === 'admin';

  // Library state
  const [libraries, setLibraries] = useState<RuleLibrary[]>([]);
  const [libLoading, setLibLoading] = useState(false);
  const [libTotal, setLibTotal] = useState(0);
  const [libPage, setLibPage] = useState(1);
  const [createLibModalOpen, setCreateLibModalOpen] = useState(false);
  const [creatingLib, setCreatingLib] = useState(false);
  const [libForm] = Form.useForm();

  // Current library (null = show library list)
  const [currentLibrary, setCurrentLibrary] = useState<RuleLibrary | null>(null);

  // Rules state (within a library)
  const [rules, setRules] = useState<Rule[]>([]);
  const [ruleLoading, setRuleLoading] = useState(false);
  const [ruleTotal, setRuleTotal] = useState(0);
  const [rulePage, setRulePage] = useState(1);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [previewModalOpen, setPreviewModalOpen] = useState(false);
  const [previewRule, setPreviewRule] = useState<Rule | null>(null);
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadForm] = Form.useForm();
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingRule, setEditingRule] = useState<Rule | null>(null);
  const [savingEdit, setSavingEdit] = useState(false);
  const [editForm] = Form.useForm();

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

  // Fetch rules for current library
  const fetchRules = async () => {
    if (!currentLibrary) return;
    setRuleLoading(true);
    try {
      const res = await getRuleList({ page: rulePage, pageSize: PAGE_SIZE, libraryId: currentLibrary.id });
      setRules(res.data.data.records);
      setRuleTotal(res.data.data.total);
    } catch { /* handled */ }
    finally { setRuleLoading(false); }
  };

  useEffect(() => {
    if (!currentLibrary) fetchLibraries();
  }, [libPage, currentLibrary]);

  useEffect(() => {
    if (currentLibrary) fetchRules();
  }, [rulePage, currentLibrary]);

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

  // Rule operations
  const handleUpload = async () => {
    if (!uploadFile || !currentLibrary) { message.warning('请选择规则文件'); return; }
    setUploading(true);
    try {
      const formData = new FormData();
      formData.append('file', uploadFile);
      formData.append('libraryId', String(currentLibrary.id));
      const res = await uploadRule(formData);
      const created = res.data?.data ?? [];
      const n = Array.isArray(created) ? created.length : 0;
      message.success(n > 1 ? `规则上传成功，共解析 ${n} 条规则` : '规则上传成功');
      setUploadModalOpen(false);
      setUploadFile(null);
      uploadForm.resetFields();
      fetchRules();
    } catch { /* handled */ }
    finally { setUploading(false); }
  };

  const openEdit = (rule: Rule) => {
    setEditingRule(rule);
    editForm.setFieldsValue({
      ruleName: rule.ruleName,
      ruleCode: rule.ruleCode || '',
      ruleType: rule.ruleType || 'global',
      standard: rule.standard || '',
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
      await updateRuleMetadata(editingRule.id, {
        ruleName: values.ruleName as string,
        ruleCode: (values.ruleCode as string) || '',
        ruleType: values.ruleType as string,
        standard: (values.standard as string) || '',
        sections: (values.sections as string[]) || [],
        keywords: (values.keywords as string[]) || [],
        description: (values.description as string) || '',
      });
      message.success('元信息已保存');
      setEditModalOpen(false);
      setEditingRule(null);
      editForm.resetFields();
      fetchRules();
    } catch { /* handled */ }
    finally { setSavingEdit(false); }
  };

  const handlePreview = (rule: Rule) => {
    setPreviewRule(rule);
    setPreviewModalOpen(true);
  };

  const handleDeleteRule = async (id: number) => {
    try {
      await deleteRule(id);
      message.success('规则已删除');
      fetchRules();
    } catch { /* handled */ }
  };

  const enterLibrary = (lib: RuleLibrary) => {
    setCurrentLibrary(lib);
    setRulePage(1);
    setRules([]);
  };

  const backToLibraries = () => {
    setCurrentLibrary(null);
    setRulePage(1);
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

  // Resizable column widths — persisted per-column key.
  const DEFAULT_COL_WIDTHS: Record<string, number> = {
    ruleName: 200,
    ruleType: 90,
    scope: 220,
    sourceFile: 140,
    updatedAt: 150,
    action: 120,
  };
  const [colWidths, setColWidths] = useState<Record<string, number>>(DEFAULT_COL_WIDTHS);

  const handleColumnResize = (key: string) => (newWidth: number) => {
    setColWidths((prev) => ({ ...prev, [key]: newWidth }));
  };

  // Rule list columns
  const ruleColumns: ColumnsType<Rule> = [
    {
      title: '规则名称', key: 'ruleName',
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
      render: (_, record) => renderRuleTypeTag(record.ruleType),
    },
    {
      title: '适用范围', key: 'scope',
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
      ellipsis: true,
      render: (text?: string) => text ? <Text type="secondary" style={{ fontSize: 12 }}>{text}</Text> : '-',
    },
    {
      title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt',
      render: (text: string) => text ? new Date(text).toLocaleString('zh-CN') : '-',
    },
    {
      title: '操作', key: 'action',
      render: (_, record) => (
        <Space size={4}>
          <Tooltip title="预览">
            <Button type="text" size="small" icon={<EyeOutlined />}
              onClick={() => handlePreview(record)} />
          </Tooltip>
          {canManage && (
            <Tooltip title="编辑">
              <Button type="text" size="small" icon={<EditOutlined />}
                onClick={() => openEdit(record)} />
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

  // Attach current width + onHeaderCell for the resizable header component.
  const resizableRuleColumns: ColumnsType<Rule> = ruleColumns.map((col) => {
    const key = (col as ColumnType<Rule>).key as string;
    const width = colWidths[key];
    return {
      ...col,
      width,
      onHeaderCell: () => ({
        width,
        onResize: handleColumnResize(key),
      } as React.HTMLAttributes<HTMLElement>),
    };
  });

  const ruleTableScrollX = Object.values(colWidths).reduce((a, b) => a + b, 0);

  // LIBRARY LIST VIEW
  if (!currentLibrary) {
    return (
      <div>
        <div className="page-header">
          <Title level={4} style={{ margin: 0 }}>审查规则管理</Title>
          {canManage && (
            <Button type="primary" icon={<PlusOutlined />}
              onClick={() => setCreateLibModalOpen(true)}>新建规则库</Button>
          )}
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
              pagination={{
                current: libPage, pageSize: PAGE_SIZE, total: libTotal,
                showTotal: (t) => `共 ${t} 个规则库`, onChange: (p) => setLibPage(p), showSizeChanger: false,
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

  // RULE LIST VIEW (inside a library)
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
        {canManage && (
          <Button type="primary" icon={<PlusOutlined />}
            onClick={() => setUploadModalOpen(true)}>上传规则</Button>
        )}
      </div>

      <Card className="rule-table-card">
        {rules.length === 0 && !ruleLoading ? (
          <Empty description="该规则库暂无规则">
            {canManage && (
              <Button type="primary" onClick={() => setUploadModalOpen(true)}>上传规则</Button>
            )}
          </Empty>
        ) : (
          <Table
            columns={resizableRuleColumns}
            dataSource={rules}
            rowKey="id"
            loading={ruleLoading}
            size="middle"
            tableLayout="fixed"
            components={{ header: { cell: ResizableHeaderCell } }}
            scroll={{ x: ruleTableScrollX }}
            pagination={{
              current: rulePage, pageSize: PAGE_SIZE, total: ruleTotal,
              showTotal: (t) => `共 ${t} 条规则`, onChange: (p) => setRulePage(p), showSizeChanger: false,
            }}
          />
        )}
      </Card>

      {/* Upload Rule Modal */}
      <Modal title={`上传规则到「${currentLibrary.name}」`} open={uploadModalOpen}
        onCancel={() => { setUploadModalOpen(false); setUploadFile(null); uploadForm.resetFields(); }}
        footer={null} destroyOnClose>
        <Form form={uploadForm} onFinish={handleUpload} layout="vertical">
          <Form.Item label="规则文件" required>
            <RuleUploader onFileSelect={(file) => setUploadFile(file)} />
          </Form.Item>
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
        onCancel={() => setPreviewModalOpen(false)}
        footer={<Button onClick={() => setPreviewModalOpen(false)}>关闭</Button>} width={780}>
        {previewRule && (
          <div>
            {hasRuleMetadata(previewRule) && (
              <>
                <Title level={5}>规则元信息</Title>
                <Descriptions size="small" bordered column={2} style={{ marginBottom: 16 }}>
                  {previewRule.ruleCode && (
                    <Descriptions.Item label="规则编号">{previewRule.ruleCode}</Descriptions.Item>
                  )}
                  <Descriptions.Item label="规则类型">{renderRuleTypeTag(previewRule.ruleType)}</Descriptions.Item>
                  {previewRule.standard && (
                    <Descriptions.Item label="适用标准">{previewRule.standard}</Descriptions.Item>
                  )}
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
                { label: '输出规范规则', value: 'output' },
              ]}
            />
          </Form.Item>
          <Form.Item name="standard" label="适用标准">
            <Input placeholder="例如 DO-160G（可选）" />
          </Form.Item>
          <Form.Item
            name="sections"
            label="目标章节"
            extra="标准的章节编号（如 13、15、4.5）。按回车输入多个；仅 section_specific 规则会按此匹配文档一级标题"
          >
            <Select mode="tags" tokenSeparators={[',', '，', ' ']} placeholder="回车确认每个章节号" />
          </Form.Item>
          <Form.Item
            name="keywords"
            label="适用范围"
            extra="自定义关键词，回车确认。提交审查时会与上传文档的一级标题进行匹配，命中即认为该规则适用于该章节"
          >
            <Select mode="tags" tokenSeparators={[',', '，']} placeholder="回车确认每个关键词" />
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
    </div>
  );
}

export default RuleListPage;
