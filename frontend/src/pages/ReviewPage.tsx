import { useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card,
  Form,
  Select,
  Button,
  Steps,
  Typography,
  message,
  Progress,
  Result,
  Space,
} from 'antd';
import {
  FileTextOutlined,
  SettingOutlined,
  RocketOutlined,
  CheckCircleOutlined,
} from '@ant-design/icons';
import { getScenarioList, Scenario } from '../api/scenarios';
import { getEnabledModels, AIModel } from '../api/models';
import { submitReview } from '../api/reviews';
import FileUploader from '../components/FileUploader';
import taskWebSocket, { TaskProgressMessage } from '../utils/websocket';

const { Title, Text } = Typography;

function ReviewPage() {
  const navigate = useNavigate();
  const [currentStep, setCurrentStep] = useState(0);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [models, setModels] = useState<AIModel[]>([]);
  const [scenarioId, setScenarioId] = useState<number | undefined>();
  const [selectedModel, setSelectedModel] = useState<string | undefined>();
  const [submitting, setSubmitting] = useState(false);
  const [taskId, setTaskId] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [taskStatus, setTaskStatus] = useState<string>('');
  const progressHandlerRef = useRef<((data: TaskProgressMessage) => void) | null>(null);

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [scenRes, modelRes] = await Promise.all([
          getScenarioList({ page: 1, pageSize: 1000 }),
          getEnabledModels(),
        ]);
        setScenarios(scenRes.data.data.records);
        setModels(modelRes.data.data);
      } catch {
        // handled
      }
    };
    fetchData();
  }, []);

  const handleProgressUpdate = useCallback((data: TaskProgressMessage) => {
    setProgress(data.progress);
    setTaskStatus(data.status);
    if (data.status === 'completed' || data.status === 'COMPLETED') {
      setCurrentStep(3);
    } else if (data.status === 'failed' || data.status === 'FAILED') {
      message.error(data.message || '审查任务失败');
    }
  }, []);

  useEffect(() => {
    return () => {
      if (taskId && progressHandlerRef.current) {
        taskWebSocket.unsubscribe(taskId, progressHandlerRef.current);
      }
    };
  }, [taskId]);

  const handleSubmit = async () => {
    if (!selectedFile) {
      message.warning('请先上传文件');
      return;
    }
    if (!scenarioId) {
      message.warning('请选择审查场景');
      return;
    }
    if (!selectedModel) {
      message.warning('请选择 AI 模型');
      return;
    }

    setSubmitting(true);
    try {
      const formData = new FormData();
      formData.append('file', selectedFile);
      formData.append('scenarioId', String(scenarioId));
      formData.append('selectedModel', selectedModel);
      const res = await submitReview(formData);
      const newTaskId = res.data.data.id;
      setTaskId(newTaskId);
      setCurrentStep(2);

      // Connect WebSocket for progress
      taskWebSocket.connect();
      progressHandlerRef.current = handleProgressUpdate;
      taskWebSocket.subscribe(newTaskId, handleProgressUpdate);
    } catch {
      // handled
    } finally {
      setSubmitting(false);
    }
  };

  const steps = [
    {
      title: '上传文件',
      icon: <FileTextOutlined />,
    },
    {
      title: '配置参数',
      icon: <SettingOutlined />,
    },
    {
      title: '审查中',
      icon: <RocketOutlined />,
    },
    {
      title: '完成',
      icon: <CheckCircleOutlined />,
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>新建文件审查</Title>
      </div>

      <Card>
        <Steps current={currentStep} items={steps} style={{ marginBottom: 32 }} />

        {currentStep === 0 && (
          <div style={{ maxWidth: 600, margin: '0 auto' }}>
            <FileUploader
              onFileSelect={(file) => {
                setSelectedFile(file);
              }}
            />
            {selectedFile && (
              <div style={{ marginTop: 16, textAlign: 'center' }}>
                <Text>
                  已选择文件：<Text strong>{selectedFile.name}</Text>
                  （{(selectedFile.size / 1024 / 1024).toFixed(2)} MB）
                </Text>
              </div>
            )}
            <div style={{ marginTop: 24, textAlign: 'center' }}>
              <Button
                type="primary"
                size="large"
                disabled={!selectedFile}
                onClick={() => setCurrentStep(1)}
              >
                下一步
              </Button>
            </div>
          </div>
        )}

        {currentStep === 1 && (
          <div style={{ maxWidth: 500, margin: '0 auto' }}>
            <Form layout="vertical" size="large">
              <Form.Item label="审查场景" required>
                <Select
                  placeholder="请选择审查场景"
                  value={scenarioId}
                  onChange={setScenarioId}
                  options={scenarios.map((s) => ({
                    label: s.name,
                    value: s.id,
                  }))}
                  showSearch
                  filterOption={(input, option) =>
                    (option?.label as string)?.toLowerCase().includes(input.toLowerCase())
                  }
                />
              </Form.Item>
              <Form.Item label="AI 模型" required>
                <Select
                  placeholder="请选择 AI 模型"
                  value={selectedModel}
                  onChange={setSelectedModel}
                  options={models.map((m) => ({
                    label: `${m.name} (${m.provider})`,
                    value: m.name,
                  }))}
                />
              </Form.Item>
            </Form>
            <div style={{ textAlign: 'center' }}>
              <Space size="middle">
                <Button size="large" onClick={() => setCurrentStep(0)}>
                  上一步
                </Button>
                <Button
                  type="primary"
                  size="large"
                  loading={submitting}
                  onClick={handleSubmit}
                >
                  提交审查
                </Button>
              </Space>
            </div>
          </div>
        )}

        {currentStep === 2 && (
          <div style={{ maxWidth: 500, margin: '0 auto', textAlign: 'center' }}>
            <RocketOutlined style={{ fontSize: 48, color: '#1677ff', marginBottom: 16 }} />
            <Title level={4}>AI 正在审查文件...</Title>
            <Text type="secondary" style={{ display: 'block', marginBottom: 24 }}>
              {selectedFile?.name}
            </Text>
            <Progress
              percent={progress}
              status={taskStatus === 'failed' || taskStatus === 'FAILED' ? 'exception' : 'active'}
              strokeColor={{ '0%': '#108ee9', '100%': '#87d068' }}
              style={{ marginBottom: 16 }}
            />
            <Text type="secondary">
              {taskStatus === 'failed' || taskStatus === 'FAILED'
                ? '审查失败，请重试'
                : `处理中... ${progress}%`}
            </Text>
            {(taskStatus === 'failed' || taskStatus === 'FAILED') && (
              <div style={{ marginTop: 16 }}>
                <Button onClick={() => { setCurrentStep(1); setProgress(0); setTaskStatus(''); }}>
                  重新提交
                </Button>
              </div>
            )}
          </div>
        )}

        {currentStep === 3 && (
          <Result
            status="success"
            title="审查完成"
            subTitle={`文件 "${selectedFile?.name}" 已审查完毕`}
            extra={[
              <Button
                type="primary"
                key="view"
                onClick={() => navigate(`/review/${taskId}`)}
              >
                查看审查结果
              </Button>,
              <Button key="new" onClick={() => {
                setCurrentStep(0);
                setSelectedFile(null);
                setScenarioId(undefined);
                setSelectedModel(undefined);
                setTaskId(null);
                setProgress(0);
                setTaskStatus('');
              }}>
                新建审查
              </Button>,
            ]}
          />
        )}
      </Card>
    </div>
  );
}

export default ReviewPage;
