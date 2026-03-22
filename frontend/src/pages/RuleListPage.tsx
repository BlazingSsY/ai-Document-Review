import { useState, useEffect } from 'react';
import {
  Card,
  Table,
  Button,
  Modal,
  Form,
  Input,
  Space,
  Typography,
  message,
  Popconfirm,
} from 'antd';
import { PlusOutlined, EyeOutlined, DeleteOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { getRuleList, uploadRule, deleteRule, getRuleDetail, Rule } from '../api/rules';
import RuleUploader from '../components/RuleUploader';
import { PAGE_SIZE } from '../utils/constants';

const { Title, Paragraph } = Typography;

function RuleListPage() {
  const [rules, setRules] = useState<Rule[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [previewModalOpen, setPreviewModalOpen] = useState(false);
  const [previewRule, setPreviewRule] = useState<Rule | null>(null);
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [form] = Form.useForm();

  const fetchRules = async () => {
    setLoading(true);
    try {
      const res = await getRuleList({ page, pageSize: PAGE_SIZE });
      setRules(res.data.data.records);
      setTotal(res.data.data.total);
    } catch {
      // handled
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchRules();
  }, [page]);

  const handleUpload = async (values: { name: string }) => {
    if (!uploadFile) {
      message.warning('请选择规则文件');
      return;
    }
    setUploading(true);
    try {
      const formData = new FormData();
      formData.append('file', uploadFile);
      formData.append('name', values.name);
      await uploadRule(formData);
      message.success('规则上传成功');
      setUploadModalOpen(false);
      setUploadFile(null);
      form.resetFields();
      fetchRules();
    } catch {
      // handled
    } finally {
      setUploading(false);
    }
  };

  const handlePreview = async (id: number) => {
    try {
      const res = await getRuleDetail(id);
      setPreviewRule(res.data.data);
      setPreviewModalOpen(true);
    } catch {
      // handled
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteRule(id);
      message.success('规则已删除');
      fetchRules();
    } catch {
      // handled
    }
  };

  const columns: ColumnsType<Rule> = [
    {
      title: '规则名称',
      dataIndex: 'name',
      key: 'name',
      width: 200,
    },
    {
      title: '文件名',
      dataIndex: 'fileName',
      key: 'fileName',
      width: 200,
      ellipsis: true,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (text: string) => text ? new Date(text).toLocaleString('zh-CN') : '-',
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 180,
      render: (text: string) => text ? new Date(text).toLocaleString('zh-CN') : '-',
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_, record) => (
        <Space>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => handlePreview(record.id)}
          >
            预览
          </Button>
          <Popconfirm
            title="确定要删除此规则吗？"
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
        <Title level={4} style={{ margin: 0 }}>审查规则管理</Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => setUploadModalOpen(true)}
        >
          上传规则
        </Button>
      </div>

      <Card>
        <Table
          columns={columns}
          dataSource={rules}
          rowKey="id"
          loading={loading}
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

      {/* Upload Modal */}
      <Modal
        title="上传审查规则"
        open={uploadModalOpen}
        onCancel={() => {
          setUploadModalOpen(false);
          setUploadFile(null);
          form.resetFields();
        }}
        footer={null}
        destroyOnClose
      >
        <Form form={form} onFinish={handleUpload} layout="vertical">
          <Form.Item
            name="name"
            label="规则名称"
            rules={[{ required: true, message: '请输入规则名称' }]}
          >
            <Input placeholder="请输入规则名称" />
          </Form.Item>
          <Form.Item label="规则文件" required>
            <RuleUploader onFileSelect={(file) => setUploadFile(file)} />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setUploadModalOpen(false)}>取消</Button>
              <Button type="primary" htmlType="submit" loading={uploading}>
                上传
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* Preview Modal */}
      <Modal
        title={`规则预览 - ${previewRule?.name || ''}`}
        open={previewModalOpen}
        onCancel={() => setPreviewModalOpen(false)}
        footer={<Button onClick={() => setPreviewModalOpen(false)}>关闭</Button>}
        width={700}
      >
        {previewRule && (
          <div>
            <Title level={5}>解析后的 Prompt</Title>
            <Card
              size="small"
              style={{
                maxHeight: 400,
                overflow: 'auto',
                background: '#fafafa',
                marginBottom: 16,
              }}
            >
              <Paragraph>
                <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontSize: 13 }}>
                  {previewRule.prompt || previewRule.content || '暂无内容'}
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
