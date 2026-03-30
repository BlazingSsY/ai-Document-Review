import { useState, useEffect } from 'react';
import {
  Card,
  Table,
  Button,
  Modal,
  Form,
  Input,
  InputNumber,
  Select,
  Switch,
  Space,
  Typography,
  Tag,
  Tooltip,
  message,
  Popconfirm,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  getModelList,
  createModel,
  updateModel,
  deleteModel,
  toggleModel,
  AIModel,
  CreateModelParams,
} from '../api/models';
import { MODEL_PROVIDERS, PAGE_SIZE } from '../utils/constants';

const { Title } = Typography;

function ModelConfigPage() {
  const [models, setModels] = useState<AIModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingModel, setEditingModel] = useState<AIModel | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();
  const [isCustomProvider, setIsCustomProvider] = useState(false);

  const fetchModels = async () => {
    setLoading(true);
    try {
      const res = await getModelList({ page, pageSize: PAGE_SIZE });
      setModels(res.data.data.records);
      setTotal(res.data.data.total);
    } catch {
      // handled
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchModels();
  }, [page]);

  const openCreateModal = () => {
    setEditingModel(null);
    setIsCustomProvider(false);
    form.resetFields();
    form.setFieldsValue({ maxTokens: 4096, temperature: 0.7, enabled: true });
    setModalOpen(true);
  };

  const openEditModal = (model: AIModel) => {
    setEditingModel(model);
    const knownProvider = MODEL_PROVIDERS.some((p) => p.value === model.provider);
    setIsCustomProvider(!knownProvider);
    form.setFieldsValue({
      name: model.name,
      providerSelect: knownProvider ? model.provider : '__custom__',
      providerCustom: knownProvider ? '' : model.provider,
      modelKey: model.modelKey,
      apiEndpoint: model.apiEndpoint,
      apiKey: model.apiKey,
      maxTokens: model.maxTokens,
      temperature: model.temperature,
      enabled: model.enabled,
    });
    setModalOpen(true);
  };

  const handleSave = async (values: Record<string, unknown>) => {
    const provider = values.providerSelect === '__custom__'
      ? (values.providerCustom as string)
      : (values.providerSelect as string);
    const params: CreateModelParams = {
      name: values.name as string,
      provider,
      modelKey: values.modelKey as string,
      apiEndpoint: values.apiEndpoint as string,
      apiKey: values.apiKey as string,
      maxTokens: values.maxTokens as number,
      temperature: values.temperature as number,
      enabled: values.enabled as boolean,
    };
    setSaving(true);
    try {
      if (editingModel) {
        await updateModel(editingModel.id, params);
        message.success('模型配置已更新');
      } else {
        await createModel(params);
        message.success('模型配置已创建');
      }
      setModalOpen(false);
      form.resetFields();
      setEditingModel(null);
      fetchModels();
    } catch {
      // handled
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteModel(id);
      message.success('模型已删除');
      fetchModels();
    } catch {
      // handled
    }
  };

  const handleToggle = async (id: number, enabled: boolean) => {
    try {
      await toggleModel(id, enabled);
      message.success(enabled ? '模型已启用' : '模型已禁用');
      fetchModels();
    } catch {
      // handled
    }
  };

  const columns: ColumnsType<AIModel> = [
    {
      title: '模型名称',
      dataIndex: 'name',
      key: 'name',
      width: 160,
    },
    {
      title: '供应商',
      dataIndex: 'provider',
      key: 'provider',
      width: 120,
      render: (val: string) => {
        const p = MODEL_PROVIDERS.find((m) => m.value === val);
        return p?.label || val;
      },
    },
    {
      title: '模型标识',
      dataIndex: 'modelKey',
      key: 'modelKey',
      width: 180,
      ellipsis: true,
    },
    {
      title: 'API 地址',
      dataIndex: 'apiEndpoint',
      key: 'apiEndpoint',
      width: 220,
      ellipsis: true,
    },
    {
      title: '最大 Token',
      dataIndex: 'maxTokens',
      key: 'maxTokens',
      width: 110,
    },
    {
      title: 'Temperature',
      dataIndex: 'temperature',
      key: 'temperature',
      width: 100,
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 90,
      render: (enabled: boolean, record) => (
        <Switch
          checked={enabled}
          checkedChildren="启用"
          unCheckedChildren="禁用"
          onChange={(checked) => handleToggle(record.id, checked)}
        />
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 140,
      render: (_, record) => (
        <Space>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => openEditModal(record)}
          >
            编辑
          </Button>
          <Popconfirm
            title="确定要删除此模型配置吗？"
            onConfirm={() => handleDelete(record.id)}
            okText="确定"
            cancelText="取消"
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>AI 模型管理</Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={openCreateModal}
        >
          添加模型
        </Button>
      </div>

      <Card>
        <Table
          columns={columns}
          dataSource={models}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1100 }}
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            total,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p) => setPage(p),
            showSizeChanger: false,
          }}
        />
      </Card>

      <Modal
        title={editingModel ? '编辑模型配置' : '添加模型配置'}
        open={modalOpen}
        onCancel={() => {
          setModalOpen(false);
          setEditingModel(null);
          form.resetFields();
        }}
        footer={null}
        destroyOnClose
        width={560}
      >
        <Form
          form={form}
          onFinish={handleSave}
          layout="vertical"
          initialValues={{ maxTokens: 4096, temperature: 0.7, enabled: true }}
        >
          <Form.Item
            name="name"
            label="模型名称"
            rules={[{ required: true, message: '请输入模型名称' }]}
          >
            <Input placeholder="例：GPT-4o" />
          </Form.Item>
          <Form.Item
            name="providerSelect"
            label="供应商"
            rules={[{ required: true, message: '请选择供应商' }]}
          >
            <Select
              placeholder="请选择供应商"
              options={[
                ...MODEL_PROVIDERS.filter((p) => p.value !== 'custom'),
                { label: '自定义供应商', value: '__custom__' },
              ]}
              onChange={(v) => setIsCustomProvider(v === '__custom__')}
            />
          </Form.Item>
          {isCustomProvider && (
            <Form.Item
              name="providerCustom"
              label="自定义供应商名称"
              rules={[{ required: true, message: '请输入供应商名称' }]}
            >
              <Input placeholder="例：deepseek、zhipu、minimax" />
            </Form.Item>
          )}
          <Form.Item
            name="modelKey"
            label="模型标识"
            rules={[{ required: true, message: '请输入模型标识' }]}
          >
            <Input placeholder="例：gpt-4o, claude-3-opus" />
          </Form.Item>
          <Form.Item
            name="apiEndpoint"
            label="API 地址"
            rules={[{ required: true, message: '请输入 API 地址' }]}
          >
            <Input placeholder="例：https://api.openai.com/v1" />
          </Form.Item>
          <Form.Item
            name="apiKey"
            label="API Key"
            rules={[{ required: !editingModel, message: '请输入 API Key' }]}
          >
            <Input.Password
              placeholder={editingModel ? '留空则不修改' : '请输入 API Key'}
            />
          </Form.Item>
          <Space size="large" style={{ width: '100%' }}>
            <Form.Item
              name="maxTokens"
              label="最大 Token"
              rules={[{ required: true, message: '请输入' }]}
            >
              <InputNumber min={100} max={128000} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item
              name="temperature"
              label={
                <span>
                  Temperature&nbsp;
                  <Tooltip title="Temperature 控制模型输出的随机性。值越低（接近0），输出越确定和保守；值越高（接近1），输出越多样和有创意。文件审查建议使用较低值（0.1~0.3）以获得更稳定的结果。">
                    <QuestionCircleOutlined style={{ color: '#8c8c8c' }} />
                  </Tooltip>
                </span>
              }
              rules={[{ required: true, message: '请输入' }]}
            >
              <InputNumber min={0} max={1} step={0.1} style={{ width: 160 }} />
            </Form.Item>
          </Space>
          <Form.Item name="enabled" label="启用状态" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="禁用" />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setModalOpen(false)}>取消</Button>
              <Button type="primary" htmlType="submit" loading={saving}>
                {editingModel ? '更新' : '创建'}
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default ModelConfigPage;
