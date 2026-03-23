import { useState, useEffect } from 'react';
import {
  Card, Table, Button, Modal, Form, Input, Checkbox, Space, Typography, Tag,
  message, Popconfirm, Descriptions, List,
} from 'antd';
import { PlusOutlined, EyeOutlined, DeleteOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { getScenarioList, createScenario, deleteScenario, getScenarioDetail, Scenario } from '../api/scenarios';
import { getRuleList, Rule } from '../api/rules';
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
  const [detailModalOpen, setDetailModalOpen] = useState(false);
  const [detailScenario, setDetailScenario] = useState<Scenario | null>(null);
  const [allRules, setAllRules] = useState<Rule[]>([]);
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm();

  const fetchScenarios = async () => {
    setLoading(true);
    try {
      const res = await getScenarioList({ page, pageSize: PAGE_SIZE });
      setScenarios(res.data.data.records);
      setTotal(res.data.data.total);
    } catch { /* handled */ } finally { setLoading(false); }
  };

  const fetchAllRules = async () => {
    try {
      const res = await getRuleList({ page: 1, pageSize: 1000 });
      setAllRules(res.data.data.records);
    } catch { /* handled */ }
  };

  useEffect(() => { fetchScenarios(); }, [page]);

  const handleCreate = async (values: { name: string; description: string; ruleIds: number[] }) => {
    if (!values.ruleIds || values.ruleIds.length === 0) {
      message.warning('请至少选择一条审查规则'); return;
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

  const handleDelete = async (id: number) => {
    try {
      await deleteScenario(id);
      message.success('场景已删除');
      fetchScenarios();
    } catch { /* handled */ }
  };

  const openCreateModal = () => { fetchAllRules(); setCreateModalOpen(true); };

  const columns: ColumnsType<Scenario> = [
    { title: '场景名称', dataIndex: 'name', key: 'name', width: 180 },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true, width: 300 },
    {
      title: '关联规则数', key: 'ruleCount', width: 120,
      render: (_, record) => <Tag color="blue">{record.ruleIds?.length ?? 0} 条</Tag>,
    },
    {
      title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (text: string) => text ? new Date(text).toLocaleString('zh-CN') : '-',
    },
    {
      title: '操作', key: 'action', width: 150,
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" icon={<EyeOutlined />}
            onClick={() => handleViewDetail(record.id)}>详情</Button>
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
          <Form.Item name="ruleIds" label="选择审查规则" rules={[{ required: true, message: '请至少选择一条规则' }]}>
            <Checkbox.Group style={{ width: '100%' }}>
              <div style={{ maxHeight: 300, overflow: 'auto' }}>
                {allRules.map((rule) => (
                  <div key={rule.id} style={{ padding: '8px 0', borderBottom: '1px solid #f0f0f0' }}>
                    <Checkbox value={rule.id}>
                      <Space direction="vertical" size={0}>
                        <Text strong>{rule.name}</Text>
                        <Text type="secondary" style={{ fontSize: 12 }}>{rule.fileName}</Text>
                      </Space>
                    </Checkbox>
                  </div>
                ))}
                {allRules.length === 0 && <Text type="secondary">暂无可用规则，请先上传规则</Text>}
              </div>
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

      <Modal title={`场景详情 - ${detailScenario?.name || ''}`} open={detailModalOpen}
        onCancel={() => setDetailModalOpen(false)}
        footer={<Button onClick={() => setDetailModalOpen(false)}>关闭</Button>} width={600}>
        {detailScenario && (
          <div>
            <Descriptions column={1} bordered size="small" style={{ marginBottom: 16 }}>
              <Descriptions.Item label="场景名称">{detailScenario.name}</Descriptions.Item>
              <Descriptions.Item label="描述">{detailScenario.description || '-'}</Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {new Date(detailScenario.createdAt).toLocaleString('zh-CN')}
              </Descriptions.Item>
            </Descriptions>
            <Title level={5}>关联规则</Title>
            <List size="small" bordered dataSource={detailScenario.rules || []}
              renderItem={(rule) => (
                <List.Item>
                  <Space>
                    <Tag color="blue">{rule.name}</Tag>
                    <Text type="secondary">{rule.fileName}</Text>
                  </Space>
                </List.Item>
              )}
              locale={{ emptyText: '暂无关联规则' }}
            />
          </div>
        )}
      </Modal>
    </div>
  );
}

export default ScenarioListPage;
