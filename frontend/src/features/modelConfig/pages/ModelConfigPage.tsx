import { useState, useEffect, useRef, useMemo } from 'react';
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
  Tabs,
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
  ModelType,
  ResponseFormatMode,
} from '../api/models';
import { MODEL_PROVIDERS, MODEL_TYPES, PAGE_SIZE } from '../../../shared/utils/constants';

const { Title, Text } = Typography;

/**
 * 模型表格列宽的基准比例。各值之和 ({@link BASE_TABLE_WIDTH}) 等于"刚好能装下所有内容
 * 的最小宽度"。运行时根据容器实际宽度等比放大：宽屏铺满，无空白；窄屏退回到这些
 * 基准 px 并显示水平滚动（操作列已 fixed: 'right'）。
 *
 * 调比例时改这里即可，columns 渲染会自动跟着伸缩。
 */
const BASE_COLUMN_WIDTHS = {
  name: 130,
  provider: 90,
  modelKey: 140,
  apiEndpoint: 200,
  temperature: 110,
  thinkingMode: 100,
  enabled: 80,
  action: 90,
} as const;
const BASE_TABLE_WIDTH = Object.values(BASE_COLUMN_WIDTHS).reduce((sum, w) => sum + w, 0);

function ModelConfigPage() {
  const [models, setModels] = useState<AIModel[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingModel, setEditingModel] = useState<AIModel | null>(null);
  const [saving, setSaving] = useState(false);
  const [activeModelType, setActiveModelType] = useState<ModelType>('chat');
  const [form] = Form.useForm();
  const [isCustomProvider, setIsCustomProvider] = useState(false);
  const [testingInForm, setTestingInForm] = useState(false);
  // Track whether the user has manually toggled the thinking switch in this form
  // session. While false, typing into modelKey can auto-suggest a value;
  // once the user flips the switch themselves we stop overriding their choice.
  const [thinkingTouched, setThinkingTouched] = useState(false);
  const thinkingMode = Form.useWatch('thinkingMode', form);
  const modelType = (Form.useWatch('modelType', form) || activeModelType) as ModelType;
  // 本地模型：用户需填写完整 URL，后端不补全 /v1、/chat/completions 等路径。
  const isLocalProvider = Form.useWatch('providerSelect', form) === 'local';
  const isChatModel = modelType === 'chat';
  const isEmbeddingModel = modelType === 'embedding';

  // Measure the table container and scale all column widths proportionally so the
  // 1080px-laptop layout and the 4K-monitor layout look the same shape — same
  // column-width ratios, no wasted whitespace on wide screens, no truncation on
  // standard screens. Falls back to BASE_TABLE_WIDTH when the container is
  // narrower than the base sum, in which case Antd renders a horizontal scroll
  // with the 操作 column pinned right.
  const tableWrapperRef = useRef<HTMLDivElement>(null);
  const [containerWidth, setContainerWidth] = useState<number>(BASE_TABLE_WIDTH);
  useEffect(() => {
    const el = tableWrapperRef.current;
    if (!el) return;
    const update = () => setContainerWidth(el.clientWidth);
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const scaledWidths = useMemo(() => {
    const scale = Math.max(1, containerWidth / BASE_TABLE_WIDTH);
    const result = {} as Record<keyof typeof BASE_COLUMN_WIDTHS, number>;
    (Object.keys(BASE_COLUMN_WIDTHS) as Array<keyof typeof BASE_COLUMN_WIDTHS>).forEach((k) => {
      result[k] = Math.floor(BASE_COLUMN_WIDTHS[k] * scale);
    });
    return result;
  }, [containerWidth]);
  const scaledTotal = useMemo(
    () => Object.values(scaledWidths).reduce((sum, w) => sum + w, 0),
    [scaledWidths],
  );
  const activeTypeLabel = MODEL_TYPES.find((item) => item.value === activeModelType)?.label || '模型';
  const tabItems = MODEL_TYPES.map((item) => ({
    key: item.value,
    label: item.label,
  }));

  const handleTabChange = (key: string) => {
    setActiveModelType(key as ModelType);
    setPage(1);
    setModels([]);
  };

  const fetchModels = async () => {
    setLoading(true);
    try {
      const res = await getModelList({ page, pageSize: PAGE_SIZE, modelType: activeModelType });
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
  }, [page, activeModelType]);

  // 本地模型无需鉴权：选中时自动填充占位 Key 并锁定输入；切换到其它供应商时，
  // 若仍是这个占位值则清空，避免把 sk-local 误带到需要真实 Key 的供应商。
  useEffect(() => {
    if (isLocalProvider) {
      form.setFieldsValue({ apiKey: 'sk-local' });
    } else if (form.getFieldValue('apiKey') === 'sk-local') {
      form.setFieldsValue({ apiKey: '' });
    }
  }, [isLocalProvider, form]);

  const openCreateModal = () => {
    setEditingModel(null);
    setIsCustomProvider(false);
    setThinkingTouched(false);
    form.resetFields();
    // 新增模型时，maxTokens 留空让用户按实际模型上下文窗口填写；temperature 默认 0.3（审查类任务偏稳定）
    form.setFieldsValue({
      modelType: activeModelType,
      maxTokens: undefined,
      embeddingDimension: undefined,
      temperature: 0.3,
      timeout: 180,
      enabled: true,
      thinkingMode: false,
      responseFormatMode: 'auto',
    });
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
      modelType: model.modelType || 'chat',
      providerSelect: knownProvider ? model.provider : '__custom__',
      providerCustom: knownProvider ? '' : model.provider,
      modelKey: model.modelKey,
      apiEndpoint: model.apiEndpoint,
      apiKey: model.apiKey,
      maxTokens: model.maxTokens,
      embeddingDimension: model.embeddingDimension,
      temperature: model.temperature,
      timeout: model.timeout || 180,
      enabled: model.enabled,
      thinkingMode: !!model.thinkingMode,
      responseFormatMode: model.responseFormatMode || 'auto',
    });
    setModalOpen(true);
  };

  /**
   * Debounced auto-suggestion: when the user finishes typing the modelKey we ask
   * the backend whether that id looks like a thinking-mode model. We only apply
   * the result if the user hasn't manually flipped the switch yet.
   */
  const handleModelKeyChange = (value: string) => {
    if (!isChatModel || thinkingTouched) return;
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
      modelType: (values.modelType as ModelType) || activeModelType,
      modelKey: values.modelKey as string,
      apiEndpoint: values.apiEndpoint as string,
      apiKey: values.apiKey as string,
      maxTokens: (values.maxTokens as number) || 4096,
      embeddingDimension: values.embeddingDimension as number | undefined,
      temperature: (values.temperature as number) ?? 0.3,
      timeout: values.timeout as number,
      enabled: values.enabled as boolean,
      thinkingMode: ((values.modelType as ModelType) || activeModelType) === 'chat' ? !!values.thinkingMode : false,
      responseFormatMode: ((values.modelType as ModelType) || activeModelType) === 'chat'
        ? ((values.responseFormatMode as ResponseFormatMode) || 'auto')
        : 'auto',
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
        'name', 'modelType', 'providerSelect', 'providerCustom', 'modelKey', 'apiEndpoint', 'apiKey', 'temperature', 'timeout', 'responseFormatMode',
      ]);
      const provider = values.providerSelect === '__custom__'
        ? (values.providerCustom as string)
        : (values.providerSelect as string);
      setTestingInForm(true);
      const res = await testModelConnection({
        id: editingModel?.id,
        name: values.name as string,
        provider,
        modelType: (values.modelType as ModelType) || activeModelType,
        modelKey: values.modelKey as string,
        apiEndpoint: values.apiEndpoint as string,
        apiKey: values.apiKey as string,
        temperature: values.temperature as number,
        timeout: values.timeout as number,
        thinkingMode: ((values.modelType as ModelType) || activeModelType) === 'chat' ? !!form.getFieldValue('thinkingMode') : false,
        responseFormatMode: ((values.modelType as ModelType) || activeModelType) === 'chat'
          ? ((form.getFieldValue('responseFormatMode') as ResponseFormatMode) || 'auto')
          : 'auto',
      });
      const data = res.data?.data;
      Modal.success({
        title: '连接成功',
        content: (
          <div style={{ fontSize: 13 }}>
            <div>解析后的请求地址：<code>{data?.resolvedUrl}</code></div>
            <div style={{ marginTop: 6 }}>响应耗时：{data?.latencyMs} ms</div>
            {data?.responseFormatMode && (
              <div style={{ marginTop: 6 }}>结构化输出：{data.responseFormatMode}</div>
            )}
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

  // Column widths are scaled at render time from BASE_COLUMN_WIDTHS — see the
  // ResizeObserver hook above. The proportions stay the same on every screen.
  const columns: ColumnsType<AIModel> = [
    {
      title: '模型名称',
      dataIndex: 'name',
      key: 'name',
      width: scaledWidths.name,
      ellipsis: true,
    },
    {
      title: '供应商',
      dataIndex: 'provider',
      key: 'provider',
      width: scaledWidths.provider,
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
      width: scaledWidths.modelKey,
      ellipsis: true,
    },
    {
      title: 'API 地址',
      dataIndex: 'apiEndpoint',
      key: 'apiEndpoint',
      width: scaledWidths.apiEndpoint,
      ellipsis: true,
    },
    {
      // Single-line Temperature column — `whiteSpace: nowrap` on cell + header
      // guards the strikethrough thinking-mode variant from breaking mid-value.
      title: 'Temperature',
      dataIndex: 'temperature',
      key: 'temperature',
      width: scaledWidths.temperature,
      align: 'center',
      onHeaderCell: () => ({ style: { whiteSpace: 'nowrap' } }),
      render: (val: number, record) => (
        <span style={{ whiteSpace: 'nowrap' }}>
          {record.modelType !== 'chat' ? (
            <Text type="secondary">不适用</Text>
          ) : record.thinkingMode ? (
            <Tooltip title="思考模式下服务端会强制使用默认 temperature（如 Kimi K2.6 = 1.0），此字段仅作展示，发送请求时会被忽略">
              <span style={{ color: '#bfbfbf', textDecoration: 'line-through' }}>{val}</span>
            </Tooltip>
          ) : (
            val
          )}
        </span>
      ),
    },
    {
      title: '思考模式',
      dataIndex: 'thinkingMode',
      key: 'thinkingMode',
      width: scaledWidths.thinkingMode,
      align: 'center',
      onHeaderCell: () => ({ style: { whiteSpace: 'nowrap' } }),
      render: (val: boolean, record) => (
        record.modelType !== 'chat'
          ? <Tag color="default">不适用</Tag>
          : val
          ? <Tag color="purple">思考</Tag>
          : <Tag color="default">普通</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: scaledWidths.enabled,
      align: 'center',
      render: (enabled: boolean, record) => (
        <Switch
          size="small"
          checked={enabled}
          onChange={(checked) => handleToggle(record.id, checked)}
        />
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: scaledWidths.action,
      align: 'center',
      fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Tooltip title="编辑">
            <Button
              type="text"
              size="small"
              icon={<EditOutlined />}
              onClick={() => openEditModal(record)}
            />
          </Tooltip>
          <Popconfirm
            title="确定要删除此模型配置吗？"
            onConfirm={() => handleDelete(record.id)}
            okText="确定"
            cancelText="取消"
          >
            <Tooltip title="删除">
              <Button type="text" size="small" danger icon={<DeleteOutlined />} />
            </Tooltip>
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
          添加{activeTypeLabel}
        </Button>
      </div>

      <Card>
        <Tabs
          className="model-config-tabs"
          type="card"
          activeKey={activeModelType}
          items={tabItems}
          onChange={handleTabChange}
        />
        <div ref={tableWrapperRef}>
          <Table
            columns={columns}
            dataSource={models}
            rowKey="id"
            loading={loading}
            // scaledTotal grows with the container, so the table fills the
            // available width on big monitors instead of leaving a dead zone.
            // When the container is narrower than BASE_TABLE_WIDTH, scale is
            // clamped to 1 → scroll.x == base sum → horizontal scrollbar.
            scroll={{ x: scaledTotal }}
            pagination={{
              current: page,
              pageSize: PAGE_SIZE,
              total,
              showTotal: (t) => `共 ${t} 条`,
              onChange: (p) => setPage(p),
              showSizeChanger: false,
            }}
          />
        </div>
      </Card>

      <Modal
        className="model-config-modal"
        title={editingModel ? `编辑${activeTypeLabel}` : `添加${activeTypeLabel}`}
        open={modalOpen}
        centered
        onCancel={() => {
          setModalOpen(false);
          setEditingModel(null);
          form.resetFields();
        }}
        footer={null}
        destroyOnClose
        width={720}
      >
        <Form
          form={form}
          onFinish={handleSave}
          layout="vertical"
          initialValues={{ modelType: 'chat', temperature: 0.3, timeout: 180, enabled: true, thinkingMode: false, responseFormatMode: 'auto' }}
        >
          <Form.Item name="modelType" hidden>
            <Input />
          </Form.Item>
          <Form.Item
            name="name"
            label="模型名称"
            rules={[{ required: true, message: '请输入模型名称' }]}
          >
            <Input placeholder="例：DeepSeek-V3" />
          </Form.Item>
          <Form.Item
            name="providerSelect"
            label="供应商"
            rules={[{ required: true, message: '请选择供应商' }]}
          >
            <Select
              placeholder="请选择供应商"
              options={[
                ...MODEL_PROVIDERS.filter((p) => p.value !== 'custom' && p.value !== 'local'),
                { label: '自定义供应商', value: '__custom__' },
                ...MODEL_PROVIDERS.filter((p) => p.value === 'local'),
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
            extra={isChatModel
              ? '填写官方模型ID，例：deepseek-chat、kimi-k2-thinking、kimi-k2.6、glm-5.1。系统会据此自动建议是否开启思考模式。'
              : isEmbeddingModel
                ? '填写向量模型ID，例：bge-m3、text-embedding-v3。'
                : '填写重排模型ID，例：bge-reranker-v2-m3。'}
          >
            <Input
              placeholder={isChatModel ? '例：deepseek-chat, kimi-k2-thinking, glm-5.1' : isEmbeddingModel ? '例：bge-m3' : '例：bge-reranker-v2-m3'}
              onBlur={(e) => handleModelKeyChange(e.target.value)}
            />
          </Form.Item>
          <Form.Item
            name="apiEndpoint"
            label="API 地址"
            rules={[{ required: true, message: '请输入 API 地址' }]}
            extra={isLocalProvider
              ? '本地模型请填写完整地址，系统不会自动补全任何路径。'
              : isChatModel
                ? '只需填写到 v1，例：https://api.minimaxi.com/v1，系统会自动补全 /chat/completions 路径'
                : isEmbeddingModel
                  ? '只需填写到 v1，系统会自动补全 /embeddings；如果供应商路径特殊，可直接填写完整 embeddings 地址。'
                  : '只需填写到 v1，系统会自动补全 /rerank；如果供应商路径特殊，可直接填写完整 rerank 地址。'}
          >
            <Input placeholder={isLocalProvider ? '例：http://192.168.1.10:8000/v1/chat/completions' : '例：https://api.minimaxi.com/v1'} />
          </Form.Item>
          {isChatModel && (
            <Form.Item
              name="responseFormatMode"
              label={
                <span>
                  结构化输出&nbsp;
                  <Tooltip title="不同供应商支持的 response_format 不同。自动模式会为 DeepSeek 使用 JSON Object、为 OpenAI 使用 JSON Schema，其他供应商使用兼容性最高的仅提示词模式；遇到格式不兼容的 HTTP 400 时还会自动降级。">
                    <QuestionCircleOutlined style={{ color: '#8c8c8c' }} />
                  </Tooltip>
                </span>
              }
              rules={[{ required: true, message: '请选择结构化输出模式' }]}
              extra="通常保持自动即可；只有供应商文档明确说明支持类型时才手动指定。"
            >
              <Select
                options={[
                  { label: '自动兼容（推荐）', value: 'auto' },
                  { label: 'JSON Schema（严格模式）', value: 'json_schema' },
                  { label: 'JSON Object（通用 JSON 模式）', value: 'json_object' },
                  { label: '仅提示词约束（兼容性最高）', value: 'prompt_only' },
                ]}
              />
            </Form.Item>
          )}
          <Form.Item
            name="apiKey"
            label="API Key"
            rules={[{ required: !editingModel, message: '请输入 API Key' }]}
            extra={isLocalProvider ? '本地模型无需鉴权，已自动填充占位 Key（sk-local），无需修改。' : undefined}
          >
            <Input.Password
              disabled={isLocalProvider}
              placeholder={editingModel ? '留空则不修改' : '请输入 API Key'}
            />
          </Form.Item>
          <Space size="large" style={{ width: '100%' }}>
            {isChatModel && (
              <Form.Item
                name="maxTokens"
                label="最大 Token"
                rules={[{ required: true, message: '请输入' }]}
                extra={thinkingMode ? '思考模式下后端会保证 ≥ 16000' : undefined}
              >
                <InputNumber min={100} max={256000} placeholder="请输入" style={{ width: 160 }} />
              </Form.Item>
            )}
            {isEmbeddingModel && (
              <Form.Item
                name="embeddingDimension"
                label="向量维度"
                extra="可选；测试连接会返回实际维度。≤2000 使用 vector HNSW，≤4000 使用 halfvec HNSW，更高维度使用二值量化 HNSW 并按原向量重排。"
              >
                <InputNumber min={1} max={16000} placeholder="例：1024" style={{ width: 160 }} />
              </Form.Item>
            )}
            {isChatModel && (
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
            )}
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
            {isChatModel && (
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
            )}
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
