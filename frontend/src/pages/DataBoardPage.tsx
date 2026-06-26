import { useEffect, useState } from 'react';
import {
  Card, Row, Col, Statistic, Button, Spin, Empty, Typography, Space, Tag,
} from 'antd';
import {
  ReloadOutlined, FileTextOutlined, CheckCircleOutlined, SyncOutlined,
  CloseCircleOutlined, ClockCircleOutlined, BugOutlined, TeamOutlined,
} from '@ant-design/icons';
import { getAdminDashboard, DashboardData, NameValue } from '../api/dashboard';

const { Title, Text } = Typography;

const PALETTE = ['#1677ff', '#52c41a', '#faad14', '#ff4d4f', '#722ed1', '#13c2c2', '#eb2f96', '#fa8c16'];
const STATUS_COLOR: Record<string, string> = {
  COMPLETED: '#52c41a', PROCESSING: '#1677ff', PENDING: '#faad14',
  FAILED: '#ff4d4f', CANCELLED: '#bfbfbf',
};

/** 纯 SVG 环形图 + 图例（无第三方依赖）。 */
function Donut({ data, colors, size = 168, thickness = 24, centerLabel = '总计' }: {
  data: NameValue[]; colors?: string[]; size?: number; thickness?: number; centerLabel?: string;
}) {
  const total = data.reduce((s, d) => s + d.value, 0);
  if (total === 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />;
  const r = (size - thickness) / 2;
  const cx = size / 2, cy = size / 2, C = 2 * Math.PI * r;
  const colorOf = (d: NameValue, i: number) =>
    (colors && colors[i]) || (d.key && STATUS_COLOR[d.key]) || PALETTE[i % PALETTE.length];
  let acc = 0;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 20, flexWrap: 'wrap' }}>
      <svg width={size} height={size} style={{ flex: '0 0 auto' }}>
        <circle cx={cx} cy={cy} r={r} fill="none" stroke="#f0f0f0" strokeWidth={thickness} />
        {data.map((d, i) => {
          const frac = d.value / total;
          const node = (
            <circle key={i} cx={cx} cy={cy} r={r} fill="none"
              stroke={colorOf(d, i)} strokeWidth={thickness}
              strokeDasharray={`${frac * C} ${C - frac * C}`}
              strokeDashoffset={-acc * C}
              transform={`rotate(-90 ${cx} ${cy})`} />
          );
          acc += frac;
          return node;
        })}
        <text x={cx} y={cy - 2} textAnchor="middle" fontSize="26" fontWeight={600} fill="#262626">{total}</text>
        <text x={cx} y={cy + 18} textAnchor="middle" fontSize="12" fill="#8c8c8c">{centerLabel}</text>
      </svg>
      <div style={{ flex: 1, minWidth: 140 }}>
        {data.map((d, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '3px 0' }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: colorOf(d, i), flex: '0 0 auto' }} />
            <span style={{ flex: 1, color: '#595959', fontSize: 13 }}>{d.name}</span>
            <span style={{ fontWeight: 600, fontSize: 13 }}>{d.value}</span>
            <span style={{ color: '#bfbfbf', fontSize: 12, width: 44, textAlign: 'right' }}>
              {Math.round((d.value / total) * 100)}%
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

/** 横向柱状（div 实现）。 */
function BarList({ data, color = '#1677ff' }: { data: NameValue[]; color?: string }) {
  if (!data.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />;
  const max = Math.max(...data.map((d) => d.value), 1);
  return (
    <div>
      {data.map((d, i) => (
        <div key={i} style={{ marginBottom: 10 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, marginBottom: 3 }}>
            <span style={{ color: '#595959', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '78%' }}>{d.name}</span>
            <span style={{ fontWeight: 600 }}>{d.value}</span>
          </div>
          <div style={{ background: '#f0f2f5', borderRadius: 4, height: 10 }}>
            <div style={{ width: `${(d.value / max) * 100}%`, background: color, height: 10, borderRadius: 4, transition: 'width .3s' }} />
          </div>
        </div>
      ))}
    </div>
  );
}

/** 近 N 天审查趋势：面积 + 折线(总量) + 折线(完成)，纯 SVG。 */
function LineTrend({ data }: { data: { date: string; total: number; completed: number }[] }) {
  if (!data.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />;
  const W = 720, H = 220, padL = 32, padR = 12, padT = 16, padB = 26;
  const max = Math.max(...data.map((d) => d.total), 1);
  const n = data.length;
  const x = (i: number) => padL + (n === 1 ? 0 : (i * (W - padL - padR)) / (n - 1));
  const y = (v: number) => padT + (1 - v / max) * (H - padT - padB);
  const line = (key: 'total' | 'completed') => data.map((d, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(d[key]).toFixed(1)}`).join(' ');
  const area = `${line('total')} L${x(n - 1).toFixed(1)},${(H - padB).toFixed(1)} L${x(0).toFixed(1)},${(H - padB).toFixed(1)} Z`;
  const ticks = 4;
  const labelEvery = Math.ceil(n / 7);
  return (
    <div style={{ width: '100%', overflowX: 'auto' }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ minWidth: 480 }}>
        {Array.from({ length: ticks + 1 }).map((_, t) => {
          const gv = Math.round((max * (ticks - t)) / ticks);
          const gy = padT + (t * (H - padT - padB)) / ticks;
          return (
            <g key={t}>
              <line x1={padL} y1={gy} x2={W - padR} y2={gy} stroke="#f0f0f0" />
              <text x={padL - 6} y={gy + 3} textAnchor="end" fontSize="10" fill="#bfbfbf">{gv}</text>
            </g>
          );
        })}
        <path d={area} fill="#1677ff" opacity={0.08} />
        <path d={line('total')} fill="none" stroke="#1677ff" strokeWidth={2} />
        <path d={line('completed')} fill="none" stroke="#52c41a" strokeWidth={2} />
        {data.map((d, i) => (
          <g key={i}>
            <circle cx={x(i)} cy={y(d.total)} r={2.5} fill="#1677ff" />
            {i % labelEvery === 0 && (
              <text x={x(i)} y={H - 8} textAnchor="middle" fontSize="10" fill="#8c8c8c">{d.date}</text>
            )}
          </g>
        ))}
      </svg>
      <Space size={16} style={{ marginTop: 4 }}>
        <span style={{ fontSize: 12, color: '#595959' }}><span style={{ display: 'inline-block', width: 10, height: 3, background: '#1677ff', marginRight: 6, verticalAlign: 'middle' }} />新增任务</span>
        <span style={{ fontSize: 12, color: '#595959' }}><span style={{ display: 'inline-block', width: 10, height: 3, background: '#52c41a', marginRight: 6, verticalAlign: 'middle' }} />已完成</span>
      </Space>
    </div>
  );
}

function ResourceStat({ label, value, suffix }: { label: string; value: number; suffix?: string }) {
  return (
    <div style={{ textAlign: 'center', padding: '8px 4px' }}>
      <div style={{ fontSize: 24, fontWeight: 600, color: '#262626' }}>{value}{suffix && <span style={{ fontSize: 13, color: '#8c8c8c' }}> {suffix}</span>}</div>
      <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 2 }}>{label}</div>
    </div>
  );
}

function DataBoardPage() {
  const [data, setData] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(false);

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await getAdminDashboard();
      setData(res.data.data);
    } catch { /* handled */ }
    finally { setLoading(false); }
  };

  useEffect(() => { fetchData(); }, []);

  const ov = data?.overview;
  const res = data?.resources;

  return (
    <div>
      <div className="page-header">
        <Space size={12} align="center">
          <Title level={4} style={{ margin: 0 }}>数据看板</Title>
          {data?.generatedAt && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              统计时间 {new Date(data.generatedAt).toLocaleString('zh-CN')}
            </Text>
          )}
        </Space>
        <Button icon={<ReloadOutlined />} onClick={fetchData} loading={loading}>刷新</Button>
      </div>

      <Spin spinning={loading}>
        {/* KPI 概览 */}
        <Row gutter={[16, 16]}>
          <Col xs={12} sm={8} lg={6}><Card><Statistic title="审查任务总数" value={ov?.totalTasks ?? 0} prefix={<FileTextOutlined style={{ color: '#1677ff' }} />} /></Card></Col>
          <Col xs={12} sm={8} lg={6}><Card><Statistic title="已完成" value={ov?.completed ?? 0} valueStyle={{ color: '#52c41a' }} prefix={<CheckCircleOutlined />} /></Card></Col>
          <Col xs={12} sm={8} lg={6}><Card><Statistic title="处理中" value={ov?.processing ?? 0} valueStyle={{ color: '#1677ff' }} prefix={<SyncOutlined />} /></Card></Col>
          <Col xs={12} sm={8} lg={6}><Card><Statistic title="失败" value={ov?.failed ?? 0} valueStyle={{ color: '#ff4d4f' }} prefix={<CloseCircleOutlined />} /></Card></Col>
          <Col xs={12} sm={8} lg={6}><Card><Statistic title="今日新增" value={ov?.todayTasks ?? 0} valueStyle={{ color: '#722ed1' }} prefix={<ClockCircleOutlined />} /></Card></Col>
          <Col xs={12} sm={8} lg={6}><Card><Statistic title="累计发现问题" value={ov?.totalProblems ?? 0} valueStyle={{ color: '#fa8c16' }} prefix={<BugOutlined />} /></Card></Col>
          <Col xs={12} sm={8} lg={6}><Card><Statistic title="平均每份问题数" value={ov?.avgProblems ?? 0} precision={1} prefix={<BugOutlined style={{ color: '#bfbfbf' }} />} /></Card></Col>
          <Col xs={12} sm={8} lg={6}><Card><Statistic title="用户数" value={res?.users ?? 0} prefix={<TeamOutlined style={{ color: '#13c2c2' }} />} /></Card></Col>
        </Row>

        {/* 趋势 + 状态分布 */}
        <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
          <Col xs={24} lg={16}>
            <Card title="近 14 天审查趋势" size="small">
              <LineTrend data={data?.dailyTrend ?? []} />
            </Card>
          </Col>
          <Col xs={24} lg={8}>
            <Card title="审查状态分布" size="small">
              <Donut data={data?.statusDistribution ?? []} centerLabel="任务" />
            </Card>
          </Col>
        </Row>

        {/* 管线分布 + 模型使用 */}
        <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
          <Col xs={24} lg={8}>
            <Card title="管线分布" size="small">
              <Donut data={data?.modeDistribution ?? []} colors={['#1677ff', '#52c41a']} centerLabel="任务" />
            </Card>
          </Col>
          <Col xs={24} lg={16}>
            <Card title="模型使用排行（按审查任务数）" size="small">
              <BarList data={data?.topModels ?? []} color="#722ed1" />
            </Card>
          </Col>
        </Row>

        {/* 资源统计 */}
        <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
          <Col xs={24} lg={16}>
            <Card title="系统资源统计" size="small">
              <Row gutter={[8, 8]}>
                <Col xs={8} sm={6}><ResourceStat label="规则数" value={res?.rules ?? 0} /></Col>
                <Col xs={8} sm={6}><ResourceStat label="原子检查项" value={res?.ruleChecks ?? 0} /></Col>
                <Col xs={8} sm={6}><ResourceStat label="规则库" value={res?.ruleLibraries ?? 0} /></Col>
                <Col xs={8} sm={6}><ResourceStat label="规则文件夹" value={res?.ruleFolders ?? 0} /></Col>
                <Col xs={8} sm={6}><ResourceStat label="审查场景" value={res?.scenarios ?? 0} /></Col>
                <Col xs={8} sm={6}>
                  <ResourceStat label="AI 模型" value={res?.models ?? 0} suffix={`/ ${res?.modelsEnabled ?? 0} 启用`} />
                </Col>
              </Row>
            </Card>
          </Col>
          <Col xs={24} lg={8}>
            <Card title="用户角色 / 模型类型" size="small">
              <div style={{ marginBottom: 8 }}>
                <Text type="secondary" style={{ fontSize: 12 }}>用户角色</Text>
                <Space wrap style={{ marginTop: 4 }}>
                  {(res?.usersByRole ?? []).map((r, i) => (
                    <Tag key={i} color={PALETTE[i % PALETTE.length]}>{r.name} {r.value}</Tag>
                  ))}
                </Space>
              </div>
              <div>
                <Text type="secondary" style={{ fontSize: 12 }}>模型类型</Text>
                <Space wrap style={{ marginTop: 4 }}>
                  {(res?.modelsByType ?? []).map((m, i) => (
                    <Tag key={i} color={PALETTE[(i + 3) % PALETTE.length]}>{m.name} {m.value}</Tag>
                  ))}
                </Space>
              </div>
            </Card>
          </Col>
        </Row>

        {!loading && !data && <Empty description="暂无看板数据" style={{ marginTop: 40 }} />}
      </Spin>
    </div>
  );
}

export default DataBoardPage;
