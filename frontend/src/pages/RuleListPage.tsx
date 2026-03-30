import { useState, useEffect } from 'react';
import {
  Card, Table, Button, Modal, Form, Input, Space, Typography, Tag,
  message, Popconfirm, Breadcrumb, Empty,
} from 'antd';
import {
  PlusOutlined, EyeOutlined, DeleteOutlined, FolderOutlined,
  ArrowLeftOutlined, FileTextOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  getRuleList, uploadRule, deleteRule, Rule, RuleLibrary,
  getRuleLibraryList, createRuleLibrary, deleteRuleLibrary,
} from '../api/rules';
import RuleUploader from '../components/RuleUploader';
import { PAGE_SIZE } from '../utils/constants';
import useAuthStore from '../store/authStore';

const { Title, Paragraph, Text } = Typography;
const { TextArea } = Input;

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
      await uploadRule(formData);
      message.success('规则上传成功');
      setUploadModalOpen(false);
      setUploadFile(null);
      uploadForm.resetFields();
      fetchRules();
    } catch { /* handled */ }
    finally { setUploading(false); }
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

  // Rule list columns
  const ruleColumns: ColumnsType<Rule> = [
    {
      title: '规则名称', key: 'ruleName', width: 200,
      render: (_, record) => (
        <Space>
          <FileTextOutlined style={{ color: '#1677ff' }} />
          <Text>{record.ruleName}</Text>
        </Space>
      ),
    },
    { title: '文件类型', dataIndex: 'fileType', key: 'fileType', width: 100 },
    {
      title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 180,
      render: (text: string) => text ? new Date(text).toLocaleString('zh-CN') : '-',
    },
    {
      title: '操作', key: 'action', width: 150,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" icon={<EyeOutlined />}
            onClick={() => handlePreview(record)}>预览</Button>
          {canManage && (
            <Popconfirm title="确定要删除此规则吗？" onConfirm={() => handleDeleteRule(record.id)}
              okText="确定" cancelText="取消">
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

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

      <Card>
        {rules.length === 0 && !ruleLoading ? (
          <Empty description="该规则库暂无规则">
            {canManage && (
              <Button type="primary" onClick={() => setUploadModalOpen(true)}>上传规则</Button>
            )}
          </Empty>
        ) : (
          <Table columns={ruleColumns} dataSource={rules} rowKey="id" loading={ruleLoading}
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
        footer={<Button onClick={() => setPreviewModalOpen(false)}>关闭</Button>} width={700}>
        {previewRule && (
          <div>
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
    </div>
  );
}

export default RuleListPage;
