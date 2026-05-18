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
import { PlusOutlined, EditOutlined, DeleteOutlined, QuestionCircleOutlined, ApiOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  getModelList,
  createModel,
  updateModel,
  deleteModel,
  toggleModel,
  testModelConnection,
  suggestThinkingMode,
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
  const [testingInForm, setTestingInForm] = useState(false);
  // Track whether the user has manually toggled the thinking switch in this form
  // session. While false, typing into modelKey can auto-suggest a value;
  // once the user flips the switch themselves we stop overriding their choice.
  const [thinkingTouched, setThinkingTouched] = useState(false);
  const thinkingMode = Form.useWatch('thinkingMode', form);

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
    setThinkingTouched(false);
    form.resetFields();
    // 新增模型时，maxTokens 留空让用户按实际模型上下文窗口填写；temperature 默认 0.3（审查类任务偏稳定）
    form.setFieldsValue({ maxTokens: undefined, temperature: 0.3, timeout: 180, enabled: true, thinkingMode: false });
    setModalOpen(true);
  };

  const openEditModal = (model: AIModel) => {
    setEditingModel(model);
    const knownProvider = MODEL_PROVIDERS.some((p) => p.value === model.provider);
    setIsCustomProvider(!knownProvider);
    // For existing models the saved value IS the user's choice, so treat the switch
    // as already-touched and never auto-overwrite while they edit modelKey.
    setThinkingTouched(true);
    form.setFieldsValue({
      name: model.name,
      providerSelect: knownProvider ? model.provider : '__custom__',
      providerCustom: knownProvider ? '' : model.provider,
      modelKey: model.modelKey,
      apiEndpoint: model.apiEndpoint,
      apiKey: model.apiKey,
      maxTokens: model.maxTokens,
      temperature: model.temperature,
      timeout: model.timeout || 180,
      enabled: model.enabled,
      thinkingMode: !!model.thinkingMode,
    });
    setModalOpen(true);
  };

  /**
   * Debounced auto-suggestion: when the user finishes typing the modelKey we ask
   * the backend whether that id looks like a thinking-mode model. We only apply
   * the result if the user hasn't manually flipped the switch yet.
   */
  const handleModelKeyChange = (value: string) => {
    if (thinkingTouched) return;
    const key = (value || '').trim();
    if (!key) return;
    suggestThinkingMode(key)
      .then((res) => {
        if (thinkingTouched) return; // user touched it while we were waiting
        const suggested = !!res.data?.data?.thinkingMode;
        const current = form.getFieldValue('thinkingMode');
        if (current !== suggested) {
          form.setFieldsValue({ thinkingMode: suggested });
        }
      })
      .catch(() => {
        // best-effort; swallow errors
      });
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
      timeout: values.timeout as number,
      enabled: values.enabled as boolean,
      thinkingMode: !!values.thinkingMode,
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

  const handleTestInForm = async () => {
    try {
      const values = await form.validateFields([
        'name', 'providerSelect', 'providerCustom', 'modelKey', 'apiEndpoint', 'apiKey', 'temperature', 'timeout',
      ]);
      const provider = values.providerSelect === '__custom__'
        ? (values.providerCustom as string)
        : (values.providerSelect as string);
      setTestingInForm(true);
      const res = await testModelConnection({
        id: editingModel?.id,
        name: values.name as string,
        provider,
        modelKey: values.modelKey as string,
        apiEndpoint: values.apiEndpoint as string,
        apiKey: values.apiKey as string,
        temperature: values.temperature as number,
        timeout: values.timeout as number,
        thinkingMode: !!form.getFieldValue('thinkingMode'),
      });
      const data = res.data?.data;
      Modal.success({
        title: '连接成功',
        content: (
          <div style={{ fontSize: 13 }}>
            <div>解析后的请求地址：<code>{data?.resolvedUrl}</code></div>
            <div style={{ marginTop: 6 }}>响应耗时：{data?.latencyMs} ms</div>
            {data?.reply && (
              <div style={{ marginTop: 6 }}>模型回复（截断）：{data.reply}</div>
            )}
          </div>
        ),
      });
    } catch (e: unknown) {
      const errMsg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (e as Error)?.message
        || '请检查地址、API Key 与模型标识';
      Modal.error({ title: '连接失败', content: errMsg });
    } finally {
      setTestingInForm(false);
    }
  };

  const columns: ColumnsType<AIModel> = [
    {
      title: '模型名称',
      dataIndex: 'name',
      key: 'name',
      width: 140,
      ellipsis: true,
    },
    {
      title: '供应商',
      dataIndex: 'provider',
      key: 'provider',
      width: 100,
      ellipsis: true,
      render: (val: string) => {
        const p = MODEL_PROVIDERS.find((m) => m.value === val);
        return p?.label || val;
      },
    },
    {
      title: '模型标识',
      dataIndex: 'modelKey',
      key: 'modelKey',
      width: 150,
      ellipsis: true,
    },
    {
      title: 'API 地址',
      dataIndex: 'apiEndpoint',
      key: 'apiEndpoint',
      width: 180,
      ellipsis: true,
    },
    {
      title: '最大 Token',
      dataIndex: 'maxTokens',
      key: 'maxTokens',
      width: 100,
    },
    {
      title: 'Temperature',
      dataIndex: 'temperature',
      key: 'temperature',
      width: 110,
      render: (val: number, record) => (
        record.thinkingMode
          ? (
            <Tooltip title="思考模式下服务端会强制使用默认 temperature（如 Kimi K2.6 = 1.0），此字段仅作展示，发送请求时会被忽略">
              <span style={{ color: '#bfbfbf', textDecoration: 'line-through' }}>{val}</span>
            </Tooltip>
          )
          : <span>{val}</span>
      ),
    },
    {
      title: '超时(s)',
      dataIndex: 'timeout',
      key: 'timeout',
      width: 100,
      render: (val: number) => val || 180,
    },
    {
      title: '思考模式',
      dataIndex: 'thinkingMode',
      key: 'thinkingMode',
      width: 100,
      render: (val: boolean) => (
        val
          ? <Tag color="purple">思考</Tag>
          : <Tag color="default">普通</Tag>
      ),
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
      width: 130,
      render: (_, record) => (
        <Space size={4}>
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
          initialValues={{ temperature: 0.3, timeout: 180, enabled: true, thinkingMode: false }}
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
            extra="填写官方模型ID，例：gpt-4o、kimi-k2-thinking、kimi-k2.6、glm-5.1。系统会据此自动建议是否开启思考模式。"
          >
            <Input
              placeholder="例：gpt-4o, claude-3-opus, kimi-k2-thinking"
              onBlur={(e) => handleModelKeyChange(e.target.value)}
            />
          </Form.Item>
          <Form.Item
            name="apiEndpoint"
            label="API 地址"
            rules={[{ required: true, message: '请输入 API 地址' }]}
            extra="只需填写到 v1，例：https://api.minimaxi.com/v1，系统会自动补全 /chat/completions 路径"
          >
            <Input placeholder="例：https://api.minimaxi.com/v1" />
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
              extra={thinkingMode ? '思考模式下后端会保证 ≥ 16000' : undefined}
            >
              <InputNumber min={100} max={256000} placeholder="请输入" style={{ width: 160 }} />
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
              extra={thinkingMode ? '已开启思考模式，发送请求时会自动省略此参数（服务端强制默认值）' : undefined}
            >
              <InputNumber min={0} max={1} step={0.1} style={{ width: 160 }} disabled={thinkingMode} />
            </Form.Item>
            <Form.Item
              name="timeout"
              label="请求超时(s)"
              rules={[{ required: true, message: '请输入' }]}
              extra="大文档或思考模型建议 180~300 秒"
            >
              <InputNumber min={30} max={900} step={30} style={{ width: 160 }} />
            </Form.Item>
          </Space>
          <Space size="large" style={{ width: '100%' }}>
            <Form.Item
              name="thinkingMode"
              label={
                <span>
                  思考模式&nbsp;
                  <Tooltip title="开启后，调用此模型时后端会自动：① 不发送 temperature（服务端会用默认值，如 Kimi K2.6/GLM-5.1 = 1.0）；② 把 max_tokens 抬到 ≥ 16000，给推理过程留足空间；③ 优先取 content 字段、必要时回退到 reasoning_content。系统会根据模型标识自动建议，也可手动调整。">
                    <QuestionCircleOutlined style={{ color: '#8c8c8c' }} />
                  </Tooltip>
                </span>
              }
              valuePropName="checked"
            >
              <Switch
                checkedChildren="开启"
                unCheckedChildren="关闭"
                onChange={() => setThinkingTouched(true)}
              />
            </Form.Item>
            <Form.Item name="enabled" label="启用状态" valuePropName="checked">
              <Switch checkedChildren="启用" unCheckedChildren="禁用" />
            </Form.Item>
          </Space>
          <Form.Item style={{ marginBottom: 0 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <Button
                icon={<ApiOutlined />}
                loading={testingInForm}
                onClick={handleTestInForm}
              >
                测试连接
              </Button>
              <Space>
                <Button onClick={() => setModalOpen(false)}>取消</Button>
                <Button type="primary" htmlType="submit" loading={saving}>
                  {editingModel ? '更新' : '创建'}
                </Button>
              </Space>
            </div>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default ModelConfigPage;
