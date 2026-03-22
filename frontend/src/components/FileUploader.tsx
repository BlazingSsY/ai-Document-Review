import { Upload, message } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import type { UploadProps } from 'antd';

const { Dragger } = Upload;

interface FileUploaderProps {
  onFileSelect: (file: File) => void;
  accept?: string;
  maxSize?: number; // MB
  description?: string;
}

function FileUploader({
  onFileSelect,
  accept = '.docx,.doc',
  maxSize = 20,
  description = '支持 .docx 格式，文件大小不超过 20MB',
}: FileUploaderProps) {
  const props: UploadProps = {
    name: 'file',
    multiple: false,
    accept,
    maxCount: 1,
    beforeUpload: (file) => {
      const isValidSize = file.size / 1024 / 1024 < maxSize;
      if (!isValidSize) {
        message.error(`文件大小不能超过 ${maxSize}MB`);
        return Upload.LIST_IGNORE;
      }
      onFileSelect(file);
      return false; // Prevent auto upload
    },
    onRemove: () => {
      // Allow removal
    },
  };

  return (
    <Dragger {...props}>
      <p className="ant-upload-drag-icon">
        <InboxOutlined />
      </p>
      <p className="ant-upload-text">点击或拖拽文件到此区域上传</p>
      <p className="ant-upload-hint">{description}</p>
    </Dragger>
  );
}

export default FileUploader;
