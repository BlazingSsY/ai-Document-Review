import { useState } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, Avatar, Dropdown, Typography, Space, Tag, theme } from 'antd';
import {
  DashboardOutlined,
  FileTextOutlined,
  AppstoreOutlined,
  AuditOutlined,
  SettingOutlined,
  UserOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import useAuthStore from '../store/authStore';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

const ROLE_TAG: Record<string, { label: string; color: string }> = {
  supervisor: { label: '主管', color: 'red' },
  admin: { label: '管理员', color: 'blue' },
  user: { label: '用户', color: 'default' },
};

function AppLayout() {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuthStore();
  const { token: themeToken } = theme.useToken();

  const role = user?.role || 'user';
  const isSupervisor = role === 'supervisor';

  const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
    navigate(key);
  };

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const menuItems: MenuProps['items'] = [
    { key: '/dashboard', icon: <DashboardOutlined />, label: '工作台' },
    { key: '/rules', icon: <FileTextOutlined />, label: '审查规则' },
    { key: '/scenarios', icon: <AppstoreOutlined />, label: '审查场景' },
    { key: '/review', icon: <AuditOutlined />, label: '文件审查' },
    { key: '/models', icon: <SettingOutlined />, label: '模型管理' },
    ...(isSupervisor ? [{ key: '/users', icon: <TeamOutlined />, label: '用户管理' }] : []),
  ];

  const userMenuItems: MenuProps['items'] = [
    {
      key: 'profile',
      icon: <UserOutlined />,
      label: '个人信息',
      onClick: () => navigate('/profile'),
    },
    { type: 'divider' as const },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: handleLogout,
    },
  ];

  const selectedKey = '/' + location.pathname.split('/')[1];
  const roleTag = ROLE_TAG[role] || ROLE_TAG.user;

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        style={{
          overflow: 'auto',
          height: '100vh',
          position: 'fixed',
          left: 0,
          top: 0,
          bottom: 0,
          background: '#ffffff',
          borderRight: '1px solid #f0f0f0',
        }}
      >
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 8,
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          <FileTextOutlined style={{ fontSize: 24, color: '#1677ff' }} />
          {!collapsed && (
            <Text strong style={{ fontSize: 15, whiteSpace: 'nowrap', color: '#1a1a2e' }}>
              AI 文件审查系统
            </Text>
          )}
        </div>
        <Menu
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={handleMenuClick}
          style={{ background: 'transparent', borderRight: 'none', marginTop: 8 }}
        />
      </Sider>
      <Layout style={{ marginLeft: collapsed ? 80 : 200, transition: 'margin-left 0.2s' }}>
        <Header
          style={{
            padding: '0 24px',
            background: '#fff',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            borderBottom: `1px solid ${themeToken.colorBorderSecondary}`,
            position: 'sticky',
            top: 0,
            zIndex: 10,
            boxShadow: '0 1px 4px rgba(0,0,0,0.04)',
          }}
        >
          <span
            onClick={() => setCollapsed(!collapsed)}
            style={{ fontSize: 18, cursor: 'pointer', color: '#595959' }}
          >
            {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          </span>
          <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
            <Space style={{ cursor: 'pointer' }}>
              <Avatar icon={<UserOutlined />} style={{ backgroundColor: themeToken.colorPrimary }} />
              <Text>{user?.name || user?.email || '用户'}</Text>
              <Tag color={roleTag.color} style={{ marginLeft: 0 }}>{roleTag.label}</Tag>
            </Space>
          </Dropdown>
        </Header>
        <Content style={{ margin: 24 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}

export default AppLayout;
