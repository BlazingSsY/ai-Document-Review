import { useState, useEffect } from 'react';
import {
  Card, Table, Tag, Select, Button, Modal, Checkbox, Form, Input, Radio,
  Typography, Space, Popconfirm, Alert, message,
} from 'antd';
import { PlusOutlined, UserOutlined, LockOutlined, MailOutlined, DeleteOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { UserInfo } from '../api/auth';
import {
  getUserList, createUser, updateUserRole, deleteUser, assignLibraries, getUserAssignedLibraries,
  type AssignmentMode,
} from '../api/users';
import type { RuleLibrary } from '../api/rules';
import { getAllRuleLibraries as getChunkLibraries } from '../api/rules';
import { getAllRuleLibraries as getSarLibraries } from '../api/sarRules';
import { PIPELINE_LABEL } from '../api/pipelineApi';

const { Title, Text } = Typography;

function UserManagementPage() {
  const [users, setUsers] = useState<UserInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);

  // Create user modal
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createForm] = Form.useForm();

  // Library assignment modal — 用户管理为 chunk / RAG 两侧分别配置规则库可见性。
  // 同一弹窗内用 Radio 切换 mode，避免一个用户开两个对话框。
  const [assignModalOpen, setAssignModalOpen] = useState(false);
  const [assignUserId, setAssignUserId] = useState<number | null>(null);
  const [assignUserName, setAssignUserName] = useState('');
  const [assignMode, setAssignMode] = useState<AssignmentMode>('CHUNK');
  const [allLibraries, setAllLibraries] = useState<RuleLibrary[]>([]);
  const [selectedLibraryIds, setSelectedLibraryIds] = useState<number[]>([]);
  const [assigning, setAssigning] = useState(false);
  const [loadingLibs, setLoadingLibs] = useState(false);

  const fetchUsers = async () => {
    setLoading(true);
    try {
      const res = await getUserList({ page, pageSize: 20 });
      setUsers(res.data.data.records);
      setTotal(res.data.data.total);
    } catch { /* handled */ }
    finally { setLoading(false); }
  };

  useEffect(() => { fetchUsers(); }, [page]);

  const handleCreateUser = async (values: { email: string; password: string; name: string; role: string }) => {
    setCreating(true);
    try {
      await createUser(values);
      message.success('用户创建成功');
      setCreateModalOpen(false);
      createForm.resetFields();
      fetchUsers();
    } catch { /* handled */ }
    finally { setCreating(false); }
  };

  const handleRoleChange = async (userId: number, role: string) => {
    try {
      await updateUserRole(userId, role);
      message.success('角色更新成功');
      fetchUsers();
    } catch { /* handled */ }
  };

  // 重新加载选中管线的全部规则库 + 该用户在该管线下的已分配规则库 ID。
  // 切换 Radio 时也复用这个函数。
  const loadAssignmentsFor = async (userId: number, mode: AssignmentMode) => {
    setLoadingLibs(true);
    try {
      const fetchLibs = mode === 'SAR' ? getSarLibraries : getChunkLibraries;
      const [libsRes, assignedRes] = await Promise.all([
        fetchLibs(),
        getUserAssignedLibraries(userId, mode),
      ]);
      setAllLibraries(libsRes.data.data);
      setSelectedLibraryIds(assignedRes.data.data || []);
    } catch { /* handled */ }
    finally { setLoadingLibs(false); }
  };

  const openAssignModal = async (user: UserInfo) => {
    setAssignUserId(user.id);
    setAssignUserName(user.name || user.email);
    setAssignMode('CHUNK');
    await loadAssignmentsFor(user.id, 'CHUNK');
    setAssignModalOpen(true);
  };

  const handleSwitchAssignMode = async (mode: AssignmentMode) => {
    if (assignUserId === null) return;
    setAssignMode(mode);
    await loadAssignmentsFor(assignUserId, mode);
  };

  const handleDeleteUser = async (userId: number) => {
    try {
      await deleteUser(userId);
      message.success('用户已删除');
      fetchUsers();
    } catch { /* handled */ }
  };

  const handleAssign = async () => {
    if (assignUserId === null) return;
    setAssigning(true);
    try {
      await assignLibraries(assignUserId, selectedLibraryIds, assignMode);
      message.success(`已保存 ${PIPELINE_LABEL[assignMode]} 规则库分配`);
      // 不关闭对话框——supervisor 通常连续在两条管线之间切换配置。
    } catch { /* handled */ }
    finally { setAssigning(false); }
  };

  const columns: ColumnsType<UserInfo> = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
    {
      title: '用户名', dataIndex: 'name', key: 'name', width: 150,
      render: (name: string, record) => name || record.email?.split('@')[0] || '-',
    },
    { title: '账号', dataIndex: 'email', key: 'email', width: 200 },
    {
      title: '角色', dataIndex: 'role', key: 'role', width: 160,
      render: (role: string, record) => {
        if (role === 'supervisor') return <Tag color="red">项目主管</Tag>;
        return (
          <Select value={role} style={{ width: 120 }}
            onChange={(v) => handleRoleChange(record.id, v)}
            options={[
              { label: '管理员', value: 'admin' },
              { label: '普通用户', value: 'user' },
            ]}
          />
        );
      },
    },
    {
      title: '注册时间', dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (text: string) => text ? new Date(text).toLocaleString('zh-CN') : '-',
    },
    {
      title: '操作', key: 'action', width: 200,
      render: (_, record) => {
        if (record.role === 'supervisor') return <Text type="secondary">-</Text>;
        return (
          <Space>
            <Button type="link" size="small" onClick={() => openAssignModal(record)}>
              分配规则库
            </Button>
            <Popconfirm title="确定要删除此用户吗？删除后不可恢复。"
              onConfirm={() => handleDeleteUser(record.id)}
              okText="确定" cancelText="取消">
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>用户管理</Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalOpen(true)}>
          创建用户
        </Button>
      </div>

      <Card>
        <Table columns={columns} dataSource={users} rowKey="id" loading={loading}
          pagination={{
            current: page, pageSize: 20, total,
            showTotal: (t) => `共 ${t} 位用户`,
            onChange: (p) => setPage(p), showSizeChanger: false,
          }}
        />
      </Card>

      {/* Create User Modal */}
      <Modal title="创建用户" open={createModalOpen}
        onCancel={() => { setCreateModalOpen(false); createForm.resetFields(); }}
        footer={null} destroyOnClose width={460}>
        <Form form={createForm} onFinish={handleCreateUser} layout="vertical"
          initialValues={{ role: 'user' }}>
          <Form.Item name="name" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="请输入用户名" />
          </Form.Item>
          <Form.Item name="email" label="账号/邮箱" rules={[{ required: true, message: '请输入账号' }]}>
            <Input prefix={<MailOutlined />} placeholder="请输入账号或邮箱" />
          </Form.Item>
          <Form.Item name="password" label="密码"
            rules={[{ required: true, message: '请输入密码' }, { min: 6, message: '密码至少6位' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="请输入初始密码" />
          </Form.Item>
          <Form.Item name="role" label="角色">
            <Select options={[
              { label: '管理员', value: 'admin' },
              { label: '普通用户', value: 'user' },
            ]} />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0, textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setCreateModalOpen(false)}>取消</Button>
              <Button type="primary" htmlType="submit" loading={creating}>创建</Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>

      {/* Library Assignment Modal — Radio 切换管线，复选框选库；保存按钮只写
          当前管线。supervisor 可来回切换两个 tab 配置同一个用户的两套权限。 */}
      <Modal title={`分配规则库 - ${assignUserName}`} open={assignModalOpen}
        onCancel={() => setAssignModalOpen(false)} onOk={handleAssign}
        confirmLoading={assigning} okText={`保存到「${PIPELINE_LABEL[assignMode]}」`} cancelText="关闭" width={520}>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Radio.Group value={assignMode} onChange={(e) => handleSwitchAssignMode(e.target.value)}>
            <Radio.Button value="CHUNK">全文逐章审查</Radio.Button>
            <Radio.Button value="SAR">结构化审查</Radio.Button>
          </Radio.Group>
          <Alert
            type="info"
            showIcon
            message={`当前正在编辑「${PIPELINE_LABEL[assignMode]}」管线下，该用户可见的规则库`}
            description="两条管线的规则库各自独立。普通用户只能看到被勾选的库及其规则；管理员/主管无视分配看到全部。"
          />
          <Checkbox.Group value={selectedLibraryIds}
            onChange={(vals) => setSelectedLibraryIds(vals as number[])} style={{ width: '100%' }}
            disabled={loadingLibs}>
            <div style={{ maxHeight: 360, overflow: 'auto' }}>
              {allLibraries.map((lib) => (
                <div key={lib.id} style={{ padding: '10px 0', borderBottom: '1px solid #f0f0f0' }}>
                  <Checkbox value={lib.id}>
                    <Space direction="vertical" size={0}>
                      <Text strong>{lib.name}</Text>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {lib.description || '无描述'} ({lib.ruleCount} 条规则)
                      </Text>
                    </Space>
                  </Checkbox>
                </div>
              ))}
              {allLibraries.length === 0 && (
                <Text type="secondary">
                  {loadingLibs ? '加载中…' : `${PIPELINE_LABEL[assignMode]} 暂无规则库`}
                </Text>
              )}
            </div>
          </Checkbox.Group>
        </Space>
      </Modal>
    </div>
  );
}

export default UserManagementPage;
