import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Button, Typography, message, Divider } from 'antd';
import { UserOutlined, LockOutlined, MailOutlined, RobotOutlined } from '@ant-design/icons';
import { login, register } from '../api/auth';
import useAuthStore from '../store/authStore';

const { Title, Text, Link } = Typography;

function LoginPage() {
  const [isRegister, setIsRegister] = useState(false);
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);

  const handleSubmit = async (values: { email: string; password: string; name?: string }) => {
    setLoading(true);
    try {
      let res;
      if (isRegister) {
        res = await register({
          email: values.email,
          password: values.password,
          name: values.name || '',
        });
      } else {
        res = await login({ email: values.email, password: values.password });
      }
      const { accessToken: token, refreshToken } = res.data.data;
      const userInfo = { id: 1, email: values.email, name: values.email.split('@')[0], role: 'user' };
      setAuth(token, userInfo);
      message.success(isRegister ? '注册成功' : '登录成功');
      navigate('/dashboard');
    } catch {
      // Error handled by interceptor
    } finally {
      setLoading(false);
    }
  };

  const toggleMode = () => {
    setIsRegister(!isRegister);
    form.resetFields();
  };

  return (
    <div className="login-container">
      <Card className="login-card" bordered={false}>
        <div style={{ textAlign: 'center', marginBottom: 8 }}>
          <RobotOutlined style={{ fontSize: 40, color: '#1677ff' }} />
        </div>
        <Title level={3} className="login-title">
          AI 智能文件审查系统
        </Title>
        <Text className="login-subtitle" style={{ display: 'block' }}>
          {isRegister ? '创建新账户' : '登录您的账户'}
        </Text>

        <Form
          form={form}
          onFinish={handleSubmit}
          layout="vertical"
          size="large"
          autoComplete="off"
        >
          {isRegister && (
            <Form.Item
              name="name"
              rules={[{ required: true, message: '请输入用户名' }]}
            >
              <Input prefix={<UserOutlined />} placeholder="用户名" />
            </Form.Item>
          )}
          <Form.Item
            name="email"
            rules={[
              { required: true, message: '请输入邮箱地址' },
              { type: 'email', message: '请输入有效的邮箱地址' },
            ]}
          >
            <Input prefix={<MailOutlined />} placeholder="邮箱地址" />
          </Form.Item>
          <Form.Item
            name="password"
            rules={[
              { required: true, message: '请输入密码' },
              { min: 6, message: '密码至少 6 位' },
            ]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          {isRegister && (
            <Form.Item
              name="confirmPassword"
              dependencies={['password']}
              rules={[
                { required: true, message: '请确认密码' },
                ({ getFieldValue }) => ({
                  validator(_, value) {
                    if (!value || getFieldValue('password') === value) {
                      return Promise.resolve();
                    }
                    return Promise.reject(new Error('两次输入的密码不一致'));
                  },
                }),
              ]}
            >
              <Input.Password prefix={<LockOutlined />} placeholder="确认密码" />
            </Form.Item>
          )}
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              {isRegister ? '注 册' : '登 录'}
            </Button>
          </Form.Item>
        </Form>

        <Divider plain>
          <Text type="secondary" style={{ fontSize: 13 }}>
            {isRegister ? '已有账户？' : '还没有账户？'}
          </Text>
        </Divider>
        <div style={{ textAlign: 'center' }}>
          <Link onClick={toggleMode}>
            {isRegister ? '返回登录' : '立即注册'}
          </Link>
        </div>
      </Card>
    </div>
  );
}

export default LoginPage;
