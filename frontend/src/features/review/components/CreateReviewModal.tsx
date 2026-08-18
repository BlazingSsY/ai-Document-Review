import { useEffect, useState } from 'react';
import {
  Modal, Steps, Form, Row, Col, Select, Switch, Tooltip, Button, Tag, Typography, message,
} from 'antd';
import {
  FileSearchOutlined, BookOutlined, PaperClipOutlined, SafetyCertificateOutlined,
  ArrowLeftOutlined, ArrowRightOutlined, RocketOutlined,
} from '@ant-design/icons';
import type { Scenario } from '../../scenarios/api/scenarios';
import { getScenarioList } from '../../scenarios/api/scenarios';
import { getEnabledModels, AIModel } from '../../modelConfig/api/models';
import { submitReview } from '../api/reviews';
import { PIPELINE_LABEL } from '../api/pipelineApi';
import type { ReviewFeatureDef } from '../registry/reviewFeatures';
import FileUploader from './FileUploader';
import '../styles/createReviewModal.css';

const { Text } = Typography;

interface CreateReviewModalProps {
  /** 要发起哪个业务域的审查。类别、文案、管线都取自它。 */
  feature: ReviewFeatureDef;
  open: boolean;
  onCancel: () => void;
  /** 提交成功后回调，用于刷新任务列表与统计。 */
  onSubmitted: () => void;
}

/**
 * 新建审查弹窗，两步式：上传文档 → 审查配置。
 * 业务域相关的文案与提交参数全部来自 feature，新增审查功能无需改动本组件。
 */
function CreateReviewModal({ feature, open, onCancel, onSubmitted }: CreateReviewModalProps) {
  const [currentStep, setCurrentStep] = useState(0);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [models, setModels] = useState<AIModel[]>([]);
  const [scenarioId, setScenarioId] = useState<number | undefined>();
  const [selectedModel, setSelectedModel] = useState<string | undefined>();
  const [qualityCheckEnabled, setQualityCheckEnabled] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  const reset = () => {
    setCurrentStep(0);
    setSelectedFile(null);
    setScenarioId(undefined);
    setSelectedModel(undefined);
  };

  // 打开时加载可选场景与对话模型；关闭时清空，避免下次打开闪现上一轮的选项。
  useEffect(() => {
    if (!open) {
      setScenarios([]);
      setModels([]);
      reset();
      return;
    }
    const fetchOptions = async () => {
      try {
        const [scenarioRes, modelRes] = await Promise.all([
          getScenarioList({ page: 1, pageSize: 1000 }),
          getEnabledModels('chat'),
        ]);
        setScenarios(scenarioRes.data.data.records);
        setModels(modelRes.data.data);
      } catch { /* 统一由请求拦截器提示 */ }
    };
    void fetchOptions();
  }, [open]);

  const handleSubmit = async () => {
    if (!selectedFile) { message.warning('请先上传文件'); return; }
    if (!scenarioId) { message.warning('请选择审查场景'); return; }
    if (!selectedModel) { message.warning('请选择 AI 模型'); return; }
    setSubmitting(true);
    try {
      const formData = new FormData();
      formData.append('file', selectedFile);
      formData.append('scenarioId', String(scenarioId));
      formData.append('selectedModel', selectedModel);
      formData.append('qualityCheckEnabled', String(qualityCheckEnabled));
      formData.append('reviewCategory', feature.category);
      await submitReview(formData);
      message.success(`审查任务已提交（${feature.label}）`);
    } catch {
      // 提交失败时同样关闭并刷新：任务可能已落库，列表刷新后用户能看到真实状态。
    } finally {
      setSubmitting(false);
      onCancel();
      onSubmitted();
    }
  };

  const fileSummary = selectedFile && (
    <div className="review-create-file">
      <span className="review-create-file__icon"><PaperClipOutlined /></span>
      <div className="review-create-file__meta">
        <Text strong ellipsis>{selectedFile.name}</Text>
        <Text type="secondary">{(selectedFile.size / 1024 / 1024).toFixed(2)} MB</Text>
      </div>
      {currentStep === 0
        ? <Tag color="success">已就绪</Tag>
        : <Button type="link" size="small" onClick={() => setCurrentStep(0)}>更换文件</Button>}
    </div>
  );

  return (
    <Modal
      className="review-create-modal"
      title={(
        <div className="review-create-modal__title">
          <span className="review-create-modal__title-icon"><FileSearchOutlined /></span>
          <div className="review-create-modal__title-copy">
            <div className="review-create-modal__title-line">
              <span className="review-create-modal__title-text">新建文件审查</span>
              <Tag color="blue">{feature.shortLabel}</Tag>
              <Tag icon={<BookOutlined />}>{PIPELINE_LABEL[feature.reviewMode]}</Tag>
            </div>
            <div className="review-create-modal__title-sub">{feature.createHint}</div>
          </div>
        </div>
      )}
      open={open}
      onCancel={() => { if (!submitting) onCancel(); }}
      footer={currentStep === 0 ? (
        <>
          <Button onClick={onCancel}>取消</Button>
          <Button
            type="primary"
            icon={<ArrowRightOutlined />}
            iconPosition="end"
            disabled={!selectedFile}
            onClick={() => setCurrentStep(1)}
          >
            下一步
          </Button>
        </>
      ) : (
        <>
          <Button icon={<ArrowLeftOutlined />} disabled={submitting} onClick={() => setCurrentStep(0)}>
            返回
          </Button>
          <Button
            type="primary"
            icon={<RocketOutlined />}
            loading={submitting}
            disabled={!scenarioId || !selectedModel}
            onClick={handleSubmit}
          >
            开始审查
          </Button>
        </>
      )}
      closable={!submitting}
      maskClosable={!submitting}
      destroyOnClose
      centered
      width={600}
    >
      <Steps
        className="review-create-modal__steps"
        size="small"
        current={currentStep}
        responsive={false}
        items={[{ title: '上传文档' }, { title: '审查配置' }]}
      />

      {currentStep === 0 && (
        <div className="review-create-stage">
          <div className="review-create-upload">
            <FileUploader
              onFileSelect={setSelectedFile}
              onFileRemove={() => setSelectedFile(null)}
              description="支持 .docx / .doc 格式，单个文件不超过 20 MB"
            />
          </div>
          {fileSummary}
        </div>
      )}

      {currentStep === 1 && (
        <div className="review-create-stage">
          {fileSummary}
          <Form className="review-create-form" layout="vertical" requiredMark={false}>
            <Row gutter={16}>
              <Col xs={24} md={12}>
                <Form.Item label="审查场景" required>
                  <Select
                    placeholder="请选择审查场景"
                    value={scenarioId}
                    onChange={setScenarioId}
                    options={scenarios.map((scenario) => ({
                      label: scenario.name,
                      value: scenario.id,
                    }))}
                    showSearch
                    filterOption={(input, option) =>
                      (option?.label as string)?.toLowerCase().includes(input.toLowerCase())
                    }
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item label="AI 对话模型" required>
                  <Select
                    placeholder="请选择 AI 模型"
                    value={selectedModel}
                    onChange={setSelectedModel}
                    options={models.map((model) => ({
                      label: `${model.name} (${model.provider})`,
                      value: model.name,
                    }))}
                  />
                </Form.Item>
              </Col>
            </Row>

            <div className="review-create-quality">
              <span className="review-create-quality__icon"><SafetyCertificateOutlined /></span>
              <div className="review-create-quality__copy">
                <Text strong>全文质量检查</Text>
                <Text type="secondary">
                  {qualityCheckEnabled
                    ? '检查错别字、语病、术语一致性及图表引用，结果更完整。'
                    : '仅执行命中的业务规则，审查更快且消耗更少。'}
                </Text>
              </div>
              <Tooltip title="关闭后将跳过未命中业务规则章节的基础文字质量审查。">
                <Switch checked={qualityCheckEnabled} onChange={setQualityCheckEnabled} />
              </Tooltip>
            </div>
          </Form>
        </div>
      )}
    </Modal>
  );
}

export default CreateReviewModal;
