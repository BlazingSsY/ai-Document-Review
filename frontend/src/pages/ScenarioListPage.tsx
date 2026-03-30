import { useState, useEffect } from 'react';
import {
  Card, Table, Button, Modal, Form, Input, Checkbox, Space, Typography, Tag,
  message, Popconfirm, Descriptions,
} from 'antd';
import { PlusOutlined, EyeOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { getScenarioList, createScenario, updateScenario, deleteScenario, getScenarioDetail, Scenario } from '../api/scenarios';
import { getAllRuleLibraries, RuleLibrary } from '../api/rules';
import { PAGE_SIZE } from '../utils/constants';
import useAuthStore from '../store/authStore';

const { Title, Text } = Typography;
const { TextArea } = Input;

function ScenarioListPage() {
  const user = useAuthStore((s) => s.user);
  const canManage = user?.role === 'supervisor' || user?.role === 'admin';

  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingScenario, setEditingScenario] = useState<Scenario | null>(null);
  const [detailModalOpen, setDetailModalOpen] = useState(false);
  const [detailScenario, setDetailScenario] = useState<Scenario | null>(null);
  const [allLibraries, setAllLibraries] = useState<RuleLibrary[]>([]);
  const [creating, setCreating] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [form] = Form.useForm();
  const [editForm] = Form.useForm();

  const fetchScenarios = async () => {
    setLoading(true);
    try {
      const res = await getScenarioList({ page, pageSize: PAGE_SIZE });
      setScenarios(res.data.data.records);
      setTotal(res.data.data.total);
    } catch { /* handled */ } finally { setLoading(false); }
  };

  const fetchAllLibraries = async () => {
    try {
      const res = await getAllRuleLibraries();
      setAllLibraries(res.data.data);
    } catch { /* handled */ }
  };

  useEffect(() => { fetchScenarios(); }, [page]);

  const handleCreate = async (values: { name: string; description: string; libraryIds: number[] }) => {
    if (!values.libraryIds || values.libraryIds.length === 0) {
      message.warning('请至少选择一个规则库'); return;
    }
    setCreating(true);
    try {
      await createScenario(values);
      message.success('场景创建成功');
      setCreateModalOpen(false);
      form.resetFields();
      fetchScenarios();
    } catch { /* handled */ } finally { setCreating(false); }
  };

  const handleViewDetail = async (id: number) => {
    try {
      const res = await getScenarioDetail(id);
      setDetailScenario(res.data.data);
      setDetailModalOpen(true);
    } catch { /* handled */ }
  };

  const handleOpenEdit = async (id: number) => {
    await fetchAllLibraries();
    try {
      const res = await getScenarioDetail(id);
      const scenario = res.data.data;
      setEditingScenario(scenario);
      editForm.setFieldsValue({
        name: scenario.name,
        description: scenario.description,
        libraryIds: scenario.libraryIds || [],
      });
      setEditModalOpen(true);
    } catch { /* handled */ }
  };

  const handleUpdate = async (values: { name: string; description: string; libraryIds: number[] }) => {
    if (!editingScenario) return;
    if (!values.libraryIds || values.libraryIds.length === 0) {
      message.warning('请至少选择一个规则库'); return;
    }
    setUpdating(true);
    try {
      await updateScenario(editingScenario.id, values);
      message.success('场景更新成功');
      setEditModalOpen(false);
      editForm.resetFields();
      setEditingScenario(null);
      fetchScenarios();
    } catch { /* handled */ } finally { setUpdating(false); }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteScenario(id);
      message.success('场景已删除');
      fetchScenarios();
    } catch { /* handled */ }
  };

  const openCreateModal = () => { fetchAllLibraries(); setCreateModalOpen(true); };

  const renderLibraryCheckboxList = (libraries: RuleLibrary[]) => (
    <div style={{ maxHeight: 300, overflow: 'auto' }}>
      {libraries.map((lib) => (
        <div key={lib.id} style={{ padding: '8px 0', borderBottom: '1px solid #f0f0f0' }}>
          <Checkbox value={lib.id}>
            <Space direction="vertical" size={0}>
              <Text strong>{lib.name}</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {lib.description || '无描述'} ({lib.ruleCount} 条规则)
              </Text>
            </Space>
          </Checkbox>
        </div>
      ))}
      {libraries.length === 0 && <Text type="secondary">暂无规则库，请先创建规则库</Text>}
    </div>
  );

  const columns: ColumnsType<Scenario> = [
    { title: '场景名称', dataIndex: 'name', key: 'name', width: 180 },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true, width: 300 },
    {
      title: '关联规则库数', key: 'libraryCount', width: 130,
      render: (_, record) => <Tag color="blue">{record.libraryIds?.length ?? 0} 个</Tag>,
    },
    {
      title: '操作', key: 'action', width: 200,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" icon={<EyeOutlined />}
            onClick={() => handleViewDetail(record.id)}>详情</Button>
          {canManage && (
            <Button type="link" size="small" icon={<EditOutlined />}
              onClick={() => handleOpenEdit(record.id)}>编辑</Button>
          )}
          {canManage && (
            <Popconfirm title="确定要删除此场景吗？" onConfirm={() => handleDelete(record.id)}
              okText="确定" cancelText="取消">
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>审查场景管理</Title>
        {canManage && (
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>创建场景</Button>
        )}
      </div>

      <Card>
        <Table columns={columns} dataSource={scenarios} rowKey="id" loading={loading}
          pagination={{
            current: page, pageSize: PAGE_SIZE, total,
            showTotal: (t) => `共 ${t} 条`, onChange: (p) => setPage(p), showSizeChanger: false,
          }}
        />
      </Card>

      {/* Create Modal */}
      <Modal title="创建审查场景" open={createModalOpen}
        onCancel={() => { setCreateModalOpen(false); form.resetFields(); }}
        footer={null} destroyOnClose width={600}>
        <Form form={form} onFinish={handleCreate} layout="vertical">
          <Form.Item name="name" label="场景名称" rules={[{ required: true, message: '请输入场景名称' }]}>
            <Input placeholder="请输入场景名称" />
          </Form.Item>
          <Form.Item name="description" label="场景描述">
            <TextArea rows={3} placeholder="请输入场景描述（可选）" />
          </Form.Item>
          <Form.Item name="libraryIds" label="选择规则库" rules={[{ required: true, message: '请至少选择一个规则库' }]}>
            <Checkbox.Group style={{ width: '100%' }}>
              {renderLibraryCheckboxList(allLibraries)}
            </Checkbox.Group>
          </Form.Item>
          <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setCreateModalOpen(false)}>取消</Button>
              <Button type="primary" htmlType="submit" loading={creating}>创建</Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* Edit Modal */}
      <Modal title={`编辑场景 - ${editingScenario?.name || ''}`} open={editModalOpen}
        onCancel={() => { setEditModalOpen(false); editForm.resetFields(); setEditingScenario(null); }}
        footer={null} destroyOnClose width={600}>
        <Form form={editForm} onFinish={handleUpdate} layout="vertical">
          <Form.Item name="name" label="场景名称" rules={[{ required: true, message: '请输入场景名称' }]}>
            <Input placeholder="请输入场景名称" />
          </Form.Item>
          <Form.Item name="description" label="场景描述">
            <TextArea rows={3} placeholder="请输入场景描述（可选）" />
          </Form.Item>
          <Form.Item name="libraryIds" label="选择规则库（勾选/取消勾选即可增减）" rules={[{ required: true, message: '请至少选择一个规则库' }]}>
            <Checkbox.Group style={{ width: '100%' }}>
              {renderLibraryCheckboxList(allLibraries)}
            </Checkbox.Group>
          </Form.Item>
          <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
            <Space>
              <Button onClick={() => { setEditModalOpen(false); setEditingScenario(null); }}>取消</Button>
              <Button type="primary" htmlType="submit" loading={updating}>保存</Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* Detail Modal */}
      <Modal title={`场景详情 - ${detailScenario?.name || ''}`} open={detailModalOpen}
        onCancel={() => setDetailModalOpen(false)}
        footer={<Button onClick={() => setDetailModalOpen(false)}>关闭</Button>} width={600}>
        {detailScenario && (
          <div>
            <Descriptions column={1} bordered size="small" style={{ marginBottom: 16 }}>
              <Descriptions.Item label="场景名称">{detailScenario.name}</Descriptions.Item>
              <Descriptions.Item label="描述">{detailScenario.description || '-'}</Descriptions.Item>
              <Descriptions.Item label="关联规则库数">{detailScenario.libraryIds?.length ?? 0} 个</Descriptions.Item>
            </Descriptions>
            <Title level={5}>关联规则库</Title>
            <div>
              {detailScenario.libraryIds && detailScenario.libraryIds.length > 0 ? (
                <Space wrap>
                  {detailScenario.libraryIds.map((id) => {
                    const lib = allLibraries.find(l => l.id === id);
                    return <Tag key={id} color="blue">{lib ? lib.name : `规则库 #${id}`}</Tag>;
                  })}
                </Space>
              ) : (
                <Text type="secondary">暂无关联规则库</Text>
              )}
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}

export default ScenarioListPage;
