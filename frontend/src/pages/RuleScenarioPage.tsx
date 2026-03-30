import { useState } from 'react';
import { Tabs } from 'antd';
import { FileTextOutlined, AppstoreOutlined } from '@ant-design/icons';
import RuleListPage from './RuleListPage';
import ScenarioListPage from './ScenarioListPage';

function RuleScenarioPage() {
  const [activeKey, setActiveKey] = useState('scenarios');

  return (
    <Tabs
      activeKey={activeKey}
      onChange={setActiveKey}
      items={[
        {
          key: 'scenarios',
          label: (
            <span>
              <AppstoreOutlined />
              审查场景
            </span>
          ),
          children: <ScenarioListPage />,
        },
        {
          key: 'rules',
          label: (
            <span>
              <FileTextOutlined />
              审查规则
            </span>
          ),
          children: <RuleListPage />,
        },
      ]}
    />
  );
}

export default RuleScenarioPage;
