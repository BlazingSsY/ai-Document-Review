import { useState, useEffect } from 'react';
import {
  Card, Table, Tag, Select, Button, Modal, Checkbox, Form, Input,
  Typography, Space, Popconfirm, message,
} from 'antd';
import { PlusOutlined, UserOutlined, LockOutlined, MailOutlined, DeleteOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { UserInfo } from '../api/auth';
import { getUserList, createUser, updateUserRole, deleteUser, assignLibraries, getUserAssignedLibraries } from '../api/users';
import { getAllRuleLibraries, RuleLibrary } from '../api/rules';

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

  // Library assignment modal
  const [assignModalOpen, setAssignModalOpen] = useState(false);
  const [assignUserId, setAssignUserId] = useState<number | null>(null);
  const [assignUserName, setAssignUserName] = useState('');
  const [allLibraries, setAllLibraries] = useState<RuleLibrary[]>([]);
  const [selectedLibraryIds, setSelectedLibraryIds] = useState<number[]>([]);
  const [assigning, setAssigning] = useState(false);

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

  const openAssignModal = async (user: UserInfo) => {
    setAssignUserId(user.id);
    setAssignUserName(user.name || user.email);
    try {
      const [libsRes, assignedRes] = await Promise.all([
        getAllRuleLibraries(),
        getUserAssignedLibraries(user.id),
      ]);
      setAllLibraries(libsRes.data.data);
      setSelectedLibraryIds(assignedRes.data.data || []);
      setAssignModalOpen(true);
    } catch { /* handled */ }
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
      await assignLibraries(assignUserId, selectedLibraryIds);
      message.success('规则库分配成功');
      setAssignModalOpen(false);
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

      {/* Library Assignment Modal */}
      <Modal title={`分配规则库 - ${assignUserName}`} open={assignModalOpen}
        onCancel={() => setAssignModalOpen(false)} onOk={handleAssign}
        confirmLoading={assigning} okText="保存" cancelText="取消" width={500}>
        <div style={{ marginBottom: 12 }}>
          <Text type="secondary">勾选要分配给该用户的规则库：</Text>
        </div>
        <Checkbox.Group value={selectedLibraryIds}
          onChange={(vals) => setSelectedLibraryIds(vals as number[])} style={{ width: '100%' }}>
          <div style={{ maxHeight: 400, overflow: 'auto' }}>
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
            {allLibraries.length === 0 && <Text type="secondary">暂无规则库，请先创建规则库</Text>}
          </div>
        </Checkbox.Group>
      </Modal>
    </div>
  );
}

export default UserManagementPage;
