import { Button } from './Button';

interface PaginationProps {
  page: number;
  total: number;
  pageSize: number;
  onPage: (next: number) => void;
}

export function Pagination({ page, total, pageSize, onPage }: PaginationProps) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const from = (page - 1) * pageSize + 1;
  const to = Math.min(page * pageSize, total);
  return (
    <div className="flex items-center justify-between border-t border-border px-4 py-[10px] text-[12px] text-fg-muted">
      <div>
        Showing <span className="font-mono tabular-nums text-fg">{from}</span>–
        <span className="font-mono tabular-nums text-fg">{to}</span> of{' '}
        <span className="font-mono tabular-nums text-fg">{total}</span>
      </div>
      <div className="flex items-center gap-1">
        <Button variant="ghost" size="sm" icon="chevron-left" onClick={() => onPage(Math.max(1, page - 1))} disabled={page <= 1}>
          Prev
        </Button>
        <span className="px-2 font-mono text-[12px]">{page} / {pages}</span>
        <Button variant="ghost" size="sm" iconRight="chevron-right" onClick={() => onPage(Math.min(pages, page + 1))} disabled={page >= pages}>
          Next
        </Button>
      </div>
    </div>
  );
}
