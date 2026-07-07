import { Button, Empty, Spin } from 'antd';
import { ReviewWorkspaceContent } from '../workspace/components';
import { useReviewWorkspace } from '../workspace/useReviewWorkspace';
import '../styles/reviewWorkspace.css';

function ReviewWorkspacePage() {
  const workspace = useReviewWorkspace();

  if (workspace.loading) {
    return (
      <div className="review-loading">
        <Spin size="large" tip="加载中..." />
      </div>
    );
  }

  if (!workspace.task) {
    return (
      <Empty description="未找到审查任务" style={{ marginTop: 100 }}>
        <Button type="primary" onClick={workspace.goDashboard}>
          返回工作台
        </Button>
      </Empty>
    );
  }

  return <ReviewWorkspaceContent workspace={workspace} />;
}

export default ReviewWorkspacePage;
