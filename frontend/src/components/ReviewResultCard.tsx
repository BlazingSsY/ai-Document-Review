import { Card, Tag, Button, Typography, Space } from 'antd';
import { CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { ReviewFinding } from '../api/reviews';
import { SEVERITY_TAG_COLORS, SEVERITY_LABELS } from '../utils/constants';

const { Text, Paragraph } = Typography;

interface ReviewResultCardProps {
  finding: ReviewFinding;
  onAccept?: (id: number) => void;
  onReject?: (id: number) => void;
}

function ReviewResultCard({ finding, onAccept, onReject }: ReviewResultCardProps) {
  const isResolved = finding.status === 'accepted' || finding.status === 'rejected';

  return (
    <Card
      size="small"
      className={`finding-card severity-${finding.severity}`}
      style={{ opacity: isResolved ? 0.7 : 1 }}
    >
      <div className="finding-header">
        <Space>
          <Tag color={SEVERITY_TAG_COLORS[finding.severity]}>
            {SEVERITY_LABELS[finding.severity]}
          </Tag>
          {finding.category && <Tag>{finding.category}</Tag>}
        </Space>
        {isResolved && (
          <Tag color={finding.status === 'accepted' ? 'success' : 'default'}>
            {finding.status === 'accepted' ? '已采纳' : '已忽略'}
          </Tag>
        )}
      </div>

      <Paragraph style={{ marginBottom: 8, fontSize: 13 }}>
        {finding.explanation}
      </Paragraph>

      {finding.originalText && (
        <div className="original-text">
          <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>
            原文：
          </Text>
          {finding.originalText}
        </div>
      )}

      {finding.suggestion && (
        <div className="suggestion-text">
          <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>
            建议：
          </Text>
          {finding.suggestion}
        </div>
      )}

      {!isResolved && (
        <div className="finding-actions">
          <Button
            type="primary"
            size="small"
            icon={<CheckOutlined />}
            onClick={() => onAccept?.(finding.id)}
          >
            采纳
          </Button>
          <Button
            size="small"
            icon={<CloseOutlined />}
            onClick={() => onReject?.(finding.id)}
          >
            忽略
          </Button>
        </div>
      )}
    </Card>
  );
}

export default ReviewResultCard;
