import { useState } from 'react';
import { Card, Descriptions, Tag, Divider, Form, Input, Button, Typography, message } from 'antd';
import { LockOutlined, SafetyOutlined } from '@ant-design/icons';
import { changePassword } from '../api/auth';
import useAuthStore from '../store/authStore';

const { Title } = Typography;

const ROLE_MAP: Record<string, { label: string; color: string }> = {
  supervisor: { label: '项目主管', color: 'red' },
  admin: { label: '管理员', color: 'blue' },
  user: { label: '普通用户', color: 'default' },
};

function ProfilePage() {
  const user = useAuthStore((s) => s.user);
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const roleInfo = ROLE_MAP[user?.role || 'user'] || ROLE_MAP.user;

  const handleChangePassword = async (values: { oldPassword: string; newPassword: string }) => {
    setLoading(true);
    try {
      await changePassword({ oldPassword: values.oldPassword, newPassword: values.newPassword });
      message.success('密码修改成功，请重新登录');
      form.resetFields();
    } catch {
      // handled by interceptor
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>个人信息</Title>
      </div>

      <Card style={{ marginBottom: 24 }}>
        <Descriptions column={1} labelStyle={{ width: 120, fontWeight: 500 }}>
          <Descriptions.Item label="用户名">{user?.name || '-'}</Descriptions.Item>
          <Descriptions.Item label="邮箱/账号">{user?.email || '-'}</Descriptions.Item>
          <Descriptions.Item label="角色">
            <Tag color={roleInfo.color}>{roleInfo.label}</Tag>
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card>
        <Title level={5} style={{ marginBottom: 24 }}>
          <SafetyOutlined style={{ marginRight: 8 }} />
          修改密码
        </Title>
        <Form
          form={form}
          onFinish={handleChangePassword}
          layout="vertical"
          style={{ maxWidth: 400 }}
        >
          <Form.Item
            name="oldPassword"
            label="当前密码"
            rules={[{ required: true, message: '请输入当前密码' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="请输入当前密码" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 6, message: '密码至少 6 位' },
            ]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="请输入新密码" />
          </Form.Item>
          <Form.Item
            name="confirmPassword"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请确认新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次输入的密码不一致'));
                },
              }),
            ]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="请再次输入新密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading}>
              确认修改
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}

export default ProfilePage;
