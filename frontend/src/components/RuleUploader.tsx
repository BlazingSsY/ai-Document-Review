import { Upload, message } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import type { UploadProps } from 'antd';

const { Dragger } = Upload;

interface RuleUploaderProps {
  onFileSelect: (file: File) => void;
}

function RuleUploader({ onFileSelect }: RuleUploaderProps) {
  const props: UploadProps = {
    name: 'file',
    multiple: false,
    accept: '.md,.json,.txt,.yaml,.yml',
    maxCount: 1,
    beforeUpload: (file) => {
      const isValidSize = file.size / 1024 / 1024 < 5;
      if (!isValidSize) {
        message.error('规则文件大小不能超过 5MB');
        return Upload.LIST_IGNORE;
      }
      onFileSelect(file);
      return false;
    },
  };

  return (
    <Dragger {...props}>
      <p className="ant-upload-drag-icon">
        <InboxOutlined />
      </p>
      <p className="ant-upload-text">点击或拖拽规则文件到此区域上传</p>
      <p className="ant-upload-hint">支持 .md / .json / .txt / .yaml 格式，大小不超过 5MB</p>
    </Dragger>
  );
}

export default RuleUploader;
