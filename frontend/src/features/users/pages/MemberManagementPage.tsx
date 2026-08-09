import { useCallback, useEffect, useState } from 'react';
import {
  Card, Table, Tag, Button, Space, Select, Input, Modal, Form, message,
  Popconfirm, Typography, Upload, Alert, Row, Col, Statistic,
} from 'antd';
import {
  PlusOutlined, UploadOutlined, ReloadOutlined, DeleteOutlined,
  KeyOutlined, TeamOutlined, BankOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { UploadFile } from 'antd/es/upload/interface';
import {
  getUnits, createUnit, deleteUnit, getMembers, createMember,
  updateMemberRole, resetMemberPassword, deleteMember, importMembers,
  Unit, Member, MemberImportResult,
} from '../api/members';
import useAuthStore from '../../auth/store/authStore';
import { PAGE_SIZE } from '../../../shared/utils/constants';

const { Title, Text, Paragraph } = Typography;

const ROLE_LABELS: Record<string, { label: string; color: string }> = {
  supervisor: { label: '项目主管', color: 'red' },
  admin: { label: '单位管理员', color: 'blue' },
  user: { label: '普通用户', color: 'default' },
};

function MemberManagementPage() {
  const { user } = useAuthStore();
  const isSupervisor = user?.role === 'supervisor';

  const [units, setUnits] = useState<Unit[]>([]);
  const [members, setMembers] = useState<Member[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [unitFilter, setUnitFilter] = useState<number | undefined>();
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);

  const [memberModalOpen, setMemberModalOpen] = useState(false);
  const [unitModalOpen, setUnitModalOpen] = useState(false);
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<MemberImportResult | null>(null);
  const [importFile, setImportFile] = useState<UploadFile | null>(null);
  const [memberForm] = Form.useForm();
  const [unitForm] = Form.useForm();

  const fetchUnits = useCallback(async () => {
    try {
      const res = await getUnits();
      setUnits(res.data.data || []);
    } catch { /* 拦截器已提示 */ }
  }, []);

  const fetchMembers = useCallback(async () => {
    setLoading(true);
    try {
      const res = await getMembers({
        page, pageSize: PAGE_SIZE, unitId: unitFilter, keyword: keyword.trim() || undefined,
      });
      const data = res.data.data;
      setMembers(data.records || []);
      setTotal(data.total || 0);
    } catch { /* 拦截器已提示 */ } finally {
      setLoading(false);
    }
  }, [page, unitFilter, keyword]);

  useEffect(() => { void fetchUnits(); }, [fetchUnits]);
  useEffect(() => { void fetchMembers(); }, [fetchMembers]);

  const handleCreateMember = async () => {
    try {
      const values = await memberForm.validateFields();
      await createMember(values);
      message.success('成员已创建，初始密码为系统默认密码，其首次登录须修改');
      setMemberModalOpen(false);
      memberForm.resetFields();
      void fetchMembers();
    } catch (e) {
      if ((e as { errorFields?: unknown }).errorFields) return; // 表单校验失败，已就地提示
    }
  };

  const handleCreateUnit = async () => {
    try {
      const values = await unitForm.validateFields();
      await createUnit(values);
      message.success('单位已创建');
      setUnitModalOpen(false);
      unitForm.resetFields();
      void fetchUnits();
    } catch (e) {
      if ((e as { errorFields?: unknown }).errorFields) return;
    }
  };

  const handleImport = async () => {
    if (!importFile?.originFileObj) {
      message.warning('请先选择 Excel 文件');
      return;
    }
    setImporting(true);
    try {
      const res = await importMembers(importFile.originFileObj as File);
      setImportResult(res.data.data);
      void fetchMembers();
      void fetchUnits();
    } catch { /* 拦截器已提示 */ } finally {
      setImporting(false);
    }
  };

  const closeImport = () => {
    setImportModalOpen(false);
    setImportResult(null);
    setImportFile(null);
  };

  const columns: ColumnsType<Member> = [
    { title: '姓名', dataIndex: 'name', key: 'name', width: 120 },
    {
      title: '所属单位',
      dataIndex: 'unitName',
      key: 'unitName',
      width: 180,
      render: (name?: string) => name || <Text type="secondary">未分配</Text>,
    },
    {
      title: '身份证号',
      dataIndex: 'idCardMasked',
      key: 'idCardMasked',
      width: 200,
      render: (masked?: string) => (
        masked ? <Text code>{masked}</Text> : <Text type="secondary">—</Text>
      ),
    },
    {
      title: '角色',
      dataIndex: 'role',
      key: 'role',
      width: 130,
      render: (role: string, member) => {
        const meta = ROLE_LABELS[role] || ROLE_LABELS.user;
        if (role === 'supervisor') return <Tag color={meta.color}>{meta.label}</Tag>;
        return (
          <Select
            size="small"
            value={role}
            style={{ width: 118 }}
            options={[
              { label: '单位管理员', value: 'admin' },
              { label: '普通用户', value: 'user' },
            ]}
            onChange={async (value) => {
              try {
                await updateMemberRole(member.id, value);
                message.success('角色已更新');
                void fetchMembers();
              } catch { /* 拦截器已提示 */ }
            }}
          />
        );
      },
    },
    {
      title: '状态',
      key: 'status',
      width: 120,
      render: (_, member) => (member.mustChangePassword
        ? <Tag color="orange">待改初始密码</Tag>
        : <Tag color="green">已启用</Tag>),
    },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      render: (_, member) => (
        <Space size={4}>
          <Popconfirm
            title="重置为初始密码？"
            description="该成员下次登录时必须修改密码。"
            onConfirm={async () => {
              try {
                await resetMemberPassword(member.id);
                message.success('密码已重置');
                void fetchMembers();
              } catch { /* 拦截器已提示 */ }
            }}
          >
            <Button size="small" icon={<KeyOutlined />}>重置密码</Button>
          </Popconfirm>
          <Popconfirm
            title="删除该成员？"
            description="删除后其历史审查任务仍会保留。"
            onConfirm={async () => {
              try {
                await deleteMember(member.id);
                message.success('成员已删除');
                void fetchMembers();
              } catch { /* 拦截器已提示 */ }
            }}
          >
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <Title level={4} style={{ margin: 0 }}>成员管理</Title>
          <Text type="secondary">
            {isSupervisor ? '按单位管理全部成员' : '管理本单位成员'}
            ，成员以身份证号为唯一编码
          </Text>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => { void fetchMembers(); }}>刷新</Button>
          {isSupervisor && (
            <Button icon={<BankOutlined />} onClick={() => setUnitModalOpen(true)}>新建单位</Button>
          )}
          <Button icon={<UploadOutlined />} onClick={() => setImportModalOpen(true)}>Excel 导入</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setMemberModalOpen(true)}>
            新增成员
          </Button>
        </Space>
      </div>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card size="small">
            <Statistic title="成员总数" value={total} prefix={<TeamOutlined />} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="单位数" value={units.length} prefix={<BankOutlined />} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic
              title="待改初始密码"
              value={members.filter((m) => m.mustChangePassword).length}
              suffix={`/ ${members.length}`}
            />
          </Card>
        </Col>
      </Row>

      <Card>
        <Space style={{ marginBottom: 16 }} wrap>
          <Select
            allowClear
            placeholder="按单位筛选"
            style={{ width: 220 }}
            value={unitFilter}
            onChange={(value) => { setUnitFilter(value); setPage(1); }}
            options={units.map((u) => ({ label: u.name, value: u.id }))}
            // 单位管理员看到的列表后端已收敛到本单位，这里的下拉只是视觉一致。
            disabled={!isSupervisor}
          />
          <Input.Search
            allowClear
            placeholder="搜索姓名"
            style={{ width: 220 }}
            onSearch={(value) => { setKeyword(value); setPage(1); }}
          />
        </Space>

        <Table
          rowKey="id"
          columns={columns}
          dataSource={members}
          loading={loading}
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            total,
            onChange: setPage,
            showTotal: (t) => `共 ${t} 名成员`,
          }}
        />
      </Card>

      {/* 新增成员 */}
      <Modal
        title="新增成员"
        open={memberModalOpen}
        onOk={handleCreateMember}
        onCancel={() => setMemberModalOpen(false)}
        okText="创建"
        cancelText="取消"
        destroyOnHidden
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="初始密码为系统默认密码，成员首次登录时必须修改"
        />
        <Form form={memberForm} layout="vertical">
          <Form.Item name="unitId" label="所属单位" rules={[{ required: true, message: '请选择单位' }]}>
            <Select
              placeholder="请选择单位"
              options={units.map((u) => ({ label: u.name, value: u.id }))}
            />
          </Form.Item>
          <Form.Item
            name="username"
            label="姓名（同时作为登录用户名）"
            rules={[{ required: true, message: '请输入姓名' }]}
            extra="用户名在单位内唯一。若本单位已有同名成员，请加序号区分，如「张三2」。"
          >
            <Input placeholder="例如：张三" />
          </Form.Item>
          <Form.Item
            name="idCard"
            label="身份证号"
            rules={[
              { required: true, message: '请输入身份证号' },
              { len: 18, message: '身份证号应为 18 位' },
            ]}
            extra="作为成员唯一编码，全平台不可重复；列表中仅显示脱敏后的号码。"
          >
            <Input placeholder="18 位二代身份证号" maxLength={18} />
          </Form.Item>
          <Form.Item name="role" label="角色" initialValue="user">
            <Select
              options={[
                { label: '普通用户', value: 'user' },
                { label: '单位管理员', value: 'admin' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* 新建单位 */}
      <Modal
        title="新建单位"
        open={unitModalOpen}
        onOk={handleCreateUnit}
        onCancel={() => setUnitModalOpen(false)}
        okText="创建"
        cancelText="取消"
        destroyOnHidden
      >
        <Form form={unitForm} layout="vertical">
          <Form.Item name="name" label="单位名称" rules={[{ required: true, message: '请输入单位名称' }]}>
            <Input placeholder="例如：第一研究所" />
          </Form.Item>
          <Form.Item name="code" label="单位编号（可选）">
            <Input placeholder="便于与既有台账对齐" />
          </Form.Item>
          <Form.Item name="remark" label="备注（可选）">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      {/* Excel 导入 */}
      <Modal
        title="从 Excel 导入成员"
        open={importModalOpen}
        onCancel={closeImport}
        footer={importResult ? (
          <Button type="primary" onClick={closeImport}>完成</Button>
        ) : (
          <Space>
            <Button onClick={closeImport}>取消</Button>
            <Button type="primary" loading={importing} onClick={handleImport}>开始导入</Button>
          </Space>
        )}
        width={680}
        destroyOnHidden
      >
        {!importResult ? (
          <>
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message="表格格式"
              description={
                <div>
                  <Paragraph style={{ marginBottom: 4 }}>
                    首行为表头，从第二行开始为数据。四列依次为：
                  </Paragraph>
                  <Text code>单位</Text> <Text code>姓名</Text> <Text code>身份证号</Text>{' '}
                  <Text code>角色（管理员/普通用户，可空）</Text>
                  <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
                    姓名在单位内需唯一，重名请在名单中加序号区分（如张三1、张三2）。
                    身份证号全平台唯一，会校验位数与校验码。逐行导入，个别行出错不影响其余行。
                  </Paragraph>
                </div>
              }
            />
            <Upload.Dragger
              maxCount={1}
              accept=".xlsx,.xls"
              beforeUpload={() => false}
              fileList={importFile ? [importFile] : []}
              onChange={(info) => setImportFile(info.fileList[0] ?? null)}
              onRemove={() => setImportFile(null)}
            >
              <p className="ant-upload-drag-icon"><UploadOutlined /></p>
              <p className="ant-upload-text">点击或拖拽 Excel 文件到此处</p>
              <p className="ant-upload-hint">支持 .xlsx / .xls</p>
            </Upload.Dragger>
          </>
        ) : (
          <div>
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col span={12}>
                <Card size="small">
                  <Statistic
                    title="导入成功"
                    value={importResult.successCount}
                    valueStyle={{ color: '#52c41a' }}
                  />
                </Card>
              </Col>
              <Col span={12}>
                <Card size="small">
                  <Statistic
                    title="失败"
                    value={importResult.failureCount}
                    valueStyle={{ color: importResult.failureCount > 0 ? '#ff4d4f' : undefined }}
                  />
                </Card>
              </Col>
            </Row>
            {importResult.failureCount > 0 && (
              <>
                <Text strong>失败明细（可据行号修改后重新导入）</Text>
                <Table
                  size="small"
                  rowKey="rowNumber"
                  style={{ marginTop: 8 }}
                  dataSource={importResult.failed}
                  pagination={{ pageSize: 5, size: 'small' }}
                  columns={[
                    { title: '行号', dataIndex: 'rowNumber', width: 70 },
                    { title: '姓名', dataIndex: 'name', width: 100 },
                    { title: '失败原因', dataIndex: 'reason' },
                  ]}
                />
              </>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
}

export default MemberManagementPage;
