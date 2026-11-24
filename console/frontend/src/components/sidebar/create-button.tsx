import { ReactElement } from 'react';
import { useNavigate } from 'react-router-dom';
import addIcon from '@/assets/imgs/sidebar/btn_create_add.png';
import { useTranslation } from 'react-i18next';
interface CreateButtonProps {
  isCollapsed: boolean;
  isLogin?: boolean;
}
const CreateButton = ({ isCollapsed }: CreateButtonProps): ReactElement => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const handleClick = (): void => {
    // 处理 bd_vid 参数
    const bdVid = sessionStorage.getItem('bd_vid');
    navigate(
      bdVid
        ? `/space/agent-create?bd_vid=${encodeURIComponent(bdVid)}`
        : '/space/agent-create'
    );
  };
  return (
    <div className="w-full" style={{ marginTop: '27px' }}>
      <div
        data-testid="sidebar-create"
        className={`
          h-10 rounded-[10px] bg-[#6356EA] flex items-center justify-center cursor-pointer
          transition-opacity duration-200 hover:opacity-80
          ${isCollapsed ? 'w-9 h-9 mx-auto' : 'w-full'}
        `}
        onClick={handleClick}
      >
        <img src={addIcon} className="w-[14px] h-[14px]" alt="" />
        {!isCollapsed && (
          <span
            className="ml-2 text-sm font-medium leading-6 tracking-[-0.2px] text-white"
            style={{ fontFamily: 'PingFang SC' }}
          >
            {t('sidebar.create')}
          </span>
        )}
      </div>
    </div>
  );
};
export default CreateButton;
