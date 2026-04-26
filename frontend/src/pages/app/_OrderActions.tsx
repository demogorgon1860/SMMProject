import { ConfirmModal } from '../../components/ui';

// Slim wrapper around ConfirmModal — co-located with order pages so we can
// share the confirm UX between drawer Cancel/Refill and any future bulk actions.
interface ConfirmProps {
  open: boolean;
  title: React.ReactNode;
  body?: React.ReactNode;
  confirmText?: string;
  confirmVariant?: 'danger' | 'primary' | 'warn';
  loading?: boolean;
  onClose: () => void;
  onConfirm: () => void;
}

export function Confirm({ open, title, body, confirmText, confirmVariant = 'danger', loading, onClose, onConfirm }: ConfirmProps) {
  return (
    <ConfirmModal
      open={open}
      onClose={onClose}
      onConfirm={onConfirm}
      title={title}
      message={body}
      confirmText={confirmText}
      variant={confirmVariant}
      loading={loading}
    />
  );
}
