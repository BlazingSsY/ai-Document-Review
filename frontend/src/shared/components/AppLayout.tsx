import { useEffect, useState } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, Avatar, Dropdown, Typography, Space, Tag, theme, Modal } from 'antd';
import {
  DashboardOutlined,
  FileTextOutlined,
  SettingOutlined,
  UserOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  TeamOutlined,
  AppstoreOutlined,
  ProfileOutlined,
  BookOutlined,
  FundOutlined,
  FileSearchOutlined,
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import useAuthStore from '../../features/auth/store/authStore';
import useLogStore from '../../features/review/store/logStore';
import taskWebSocket, { TaskProgressMessage } from '../utils/websocket';
import { getUserProfile } from '../../features/auth/api/auth';
import { useAuthorizedReviewFeatures, useIsManager } from '../utils/permissions';
import { reviewFeaturePath } from '../../features/review/registry/reviewFeatures';
import { PIPELINE_LABEL } from '../../features/review/api/pipelineApi';

const { Header, Sider, Content } = Layout;
const { Text } = Typography;

const ROLE_TAG: Record<string, { label: string; color: string }> = {
  supervisor: { label: '平台管理员', color: 'red' },
  admin: { label: '单位管理员', color: 'blue' },
  user: { label: '用户', color: 'default' },
};

function AppLayout() {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, updateUser } = useAuthStore();
  const { token: themeToken } = theme.useToken();

  const role = user?.role || 'user';
  const isManager = useIsManager();
  const reviewFeatures = useAuthorizedReviewFeatures();
  // 只要有一个已授权功能复用共享规则库，就展示「审查配置」入口。
  const sharesRuleLibraries = reviewFeatures.some((feature) => feature.usesSharedRuleLibraries);

  // 权限以数据库为准；路由切换时刷新一次资料，使管理员刚调整的功能授权及时反映到菜单。
  useEffect(() => {
    getUserProfile()
      .then((res) => updateUser(res.data.data))
      .catch(() => { /* 统一请求拦截器处理失效会话 */ });
  }, [location.pathname, updateUser]);

  // Global log subscriber: keep accumulating WebSocket-driven log entries even
  // when the user is NOT on the workspace page, so returning later via 查看详情
  // shows the full timeline. This must live above the routed pages.
  useEffect(() => {
    taskWebSocket.connect();
    const handler = (data: TaskProgressMessage) => {
      if (!data.taskId) return;
      const s = data.status?.toUpperCase();
      const level: 'info' | 'error' | 'success' | 'warning' =
        s === 'COMPLETED' ? 'success'
        : s === 'FAILED' ? 'error'
        : s === 'CANCELLED' ? 'warning'
        : 'info';
      const time = new Date().toLocaleTimeString('zh-CN', {
        hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit',
      });
      useLogStore.getState().appendLog(data.taskId, {
        time,
        level,
        message: data.message || `状态更新: ${data.status}`,
        progress: data.progress,
      });
    };
    taskWebSocket.subscribe('*', handler);
    return () => {
      taskWebSocket.unsubscribe('*', handler);
    };
  }, []);

  // Routed pages render dialogs through body-level portals. Destroy imperative
  // dialogs and release any scroll lock when navigation unmounts their owner,
  // so a failed request or abrupt route change cannot leave a transparent
  // full-screen overlay intercepting clicks on the next page.
  useEffect(() => {
    Modal.destroyAll();
    document.body.style.removeProperty('overflow');
    document.body.style.removeProperty('width');
  }, [location.pathname]);

  const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
    navigate(key);
  };

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  // 每个已授权的审查功能自成一个一级分组，组内叶子是它使用的管线。菜单由注册表推导，
  // 新增审查功能时这里不需要任何改动。
  const featureMenuItems = reviewFeatures.map((feature) => ({
    key: `feature-${feature.slug}`,
    icon: feature.icon,
    label: feature.label,
    children: [{
      key: reviewFeaturePath(feature),
      icon: <BookOutlined />,
      label: PIPELINE_LABEL[feature.reviewMode],
    }],
  }));

  const menuItems: MenuProps['items'] = [
    { key: '/dashboard', icon: <DashboardOutlined />, label: '工作台' },
    ...featureMenuItems,
    // 场景与规则是跨业务域复用的配置资产，不从属于某一个审查功能，故独立成一级分组。
    ...(sharesRuleLibraries ? [{
      key: 'review-config-section',
      icon: <ProfileOutlined />,
      label: '审查配置',
      children: [
        { key: '/chunk/scenarios', icon: <AppstoreOutlined />, label: '审查场景' },
        { key: '/chunk/rules', icon: <FileSearchOutlined />, label: '审查规则' },
      ],
    }] : []),
    { key: '/models', icon: <SettingOutlined />, label: '模型管理' },
    ...(isManager ? [{ key: '/analytics', icon: <FundOutlined />, label: '数据看板' }] : []),
    // 成员、组织与授权合并为一个入口；单位管理员的范围由后端组织树收敛。
    ...(isManager ? [{ key: '/members', icon: <TeamOutlined />, label: '成员与权限' }] : []),
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

  // 菜单两级（分组 → 叶子），selectedKey 必须是叶子的路由。
  const path = location.pathname;
  const leafKeys = [
    ...reviewFeatures.map(reviewFeaturePath),
    '/chunk/scenarios',
    '/chunk/rules',
  ];
  const selectedKey = leafKeys.find((key) => path.startsWith(key)) ?? '/' + path.split('/')[1];
  // 详情页 /review/:taskId 不是菜单项；分组默认全开，离开详情页后高亮自然回到对应叶子。
  const openKeys = [
    ...reviewFeatures.map((feature) => `feature-${feature.slug}`),
    'review-config-section',
  ];
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
          defaultOpenKeys={openKeys}
          items={menuItems}
          onClick={handleMenuClick}
          // 两级菜单在 200px 宽的侧栏里用默认 24px 缩进会让叶子标签换行，18px 既保留
          // 层级感又不截断文字。
          inlineIndent={18}
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
