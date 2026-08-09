import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Button, Typography, message, Divider, Select } from 'antd';
import { UserOutlined, LockOutlined, MailOutlined, RobotOutlined, BankOutlined } from '@ant-design/icons';
import { login, register, getUserProfile, getLoginUnits, LoginUnit } from '../api/auth';
import useAuthStore from '../store/authStore';

const { Title, Text, Link } = Typography;

function LoginPage() {
  const [isRegister, setIsRegister] = useState(false);
  const [loading, setLoading] = useState(false);
  const [units, setUnits] = useState<LoginUnit[]>([]);
  const [form] = Form.useForm();
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);

  // 单位列表决定登录表单长什么样：一个单位都没有（全新部署、还没导入成员）时
  // 不显示单位选择，避免让人以为必须先建单位才能用 admin_root 登录。
  useEffect(() => {
    getLoginUnits()
      .then((res) => setUnits(res.data.data || []))
      .catch(() => setUnits([]));
  }, []);

  const handleSubmit = async (values: {
    email: string; password: string; name?: string; unitId?: number;
  }) => {
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
        res = await login({
          email: values.email,
          password: values.password,
          unitId: values.unitId,
        });
      }
      const { accessToken: token, refreshToken, mustChangePassword } = res.data.data;
      // Temporarily store tokens so getUserProfile and the refresh interceptor can use them
      localStorage.setItem('token', token);
      localStorage.setItem('refreshToken', refreshToken);

      // Fetch real user info from server
      const profileRes = await getUserProfile();
      const userInfo = profileRes.data.data;
      setAuth(token, refreshToken, userInfo);

      // 统一初始密码在改掉之前，任何知道规则的人都能登进别人的账号，所以这里直接
      // 把用户送到个人中心改密，而不是提示一下就放行。
      if (mustChangePassword === 'true') {
        message.warning('您正在使用初始密码，请立即修改');
        navigate('/profile');
        return;
      }
      message.success(isRegister ? '注册成功' : '登录成功');
      navigate('/dashboard');
    } catch {
      localStorage.removeItem('token');
      localStorage.removeItem('refreshToken');
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
      <div className="login-bg-shape login-bg-shape-1" />
      <div className="login-bg-shape login-bg-shape-2" />
      <div className="login-bg-shape login-bg-shape-3" />
      <Card className="login-card" bordered={false}>
        <div style={{ textAlign: 'center', marginBottom: 8 }}>
          <div className="login-logo">
            <RobotOutlined style={{ fontSize: 36, color: '#fff' }} />
          </div>
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
          {!isRegister && units.length > 0 && (
            <Form.Item
              name="unitId"
              extra={
                <span style={{ fontSize: 12 }}>
                  平台管理账号（邮箱登录）无需选择单位
                </span>
              }
            >
              <Select
                allowClear
                showSearch
                placeholder="请选择所属单位"
                prefix={<BankOutlined />}
                optionFilterProp="label"
                options={units.map((u) => ({ label: u.name, value: u.id }))}
              />
            </Form.Item>
          )}
          <Form.Item
            name="email"
            rules={[
              { required: true, message: '请输入邮箱地址/用户名' },
            ]}
          >
            <Input prefix={<MailOutlined />} placeholder="邮箱地址 / 用户名" />
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
      <div className="login-footer">Powered by AI Review System</div>
    </div>
  );
}

export default LoginPage;
