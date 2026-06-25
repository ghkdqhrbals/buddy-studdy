import { useState } from "react";
import type { ReactNode } from "react";

type PaginationProps = {
  start: number;
  end: number;
  totalCount: number;
  currentPage: number;
  totalPages: number;
  hrefForPage: (page: number) => string;
  onPageChange: (page: number) => void;
};

export function Pagination({
  start,
  end,
  totalCount,
  currentPage,
  totalPages,
  hrefForPage,
  onPageChange,
}: PaginationProps) {
  const [jumpPage, setJumpPage] = useState("");
  const pageItems = paginationItems(currentPage, totalPages);
  const submitJump = () => {
    const parsed = Number(jumpPage);
    if (!Number.isFinite(parsed)) return;
    const nextPage = Math.max(1, Math.min(totalPages, Math.trunc(parsed)));
    setJumpPage("");
    onPageChange(nextPage);
  };

  return (
    <div className="pagination-bar">
      <span className="pagination-summary">
        <b>{start}-{end}</b>
        {" "}
        <span>of {totalCount}</span>
        {" "}
        <span aria-hidden="true">·</span>
        {" "}
        <span>{pageCountLabel(totalPages)}</span>
      </span>
      <div className="pagination-controls">
        <PageAnchor
          disabled={currentPage <= 1}
          href={hrefForPage(Math.max(1, currentPage - 1))}
          label="Previous page"
          onClick={() => onPageChange(Math.max(1, currentPage - 1))}
        >
          <Chevron direction="left" />
        </PageAnchor>
        {pageItems.map((item, index) => (
          item === "ellipsis" ? (
            <span className="page-ellipsis" key={`ellipsis-${index}`}>...</span>
          ) : (
            <PageAnchor
              key={item}
              active={item === currentPage}
              href={hrefForPage(item)}
              onClick={() => onPageChange(item)}
            >
              {item}
            </PageAnchor>
          )
        ))}
        <PageAnchor
          disabled={currentPage >= totalPages}
          href={hrefForPage(Math.min(totalPages, currentPage + 1))}
          label="Next page"
          onClick={() => onPageChange(Math.min(totalPages, currentPage + 1))}
        >
          <Chevron direction="right" />
        </PageAnchor>
        {totalPages > 7 ? (
          <label className="page-jump">
            <span>Page</span>
            <input
              inputMode="numeric"
              pattern="[0-9]*"
              min={1}
              max={totalPages}
              value={jumpPage}
              placeholder={String(currentPage)}
              aria-label="Jump to page"
              onChange={(event) => setJumpPage(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") submitJump();
              }}
            />
            <button type="button" className="page-jump-button" onClick={submitJump}>Go</button>
          </label>
        ) : null}
      </div>
    </div>
  );
}

function PageAnchor({
  active = false,
  disabled = false,
  href,
  label,
  onClick,
  children,
}: {
  active?: boolean;
  disabled?: boolean;
  href: string;
  label?: string;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <a
      className={[
        "page-button",
        label ? "icon-page" : "",
        active ? "active" : "",
        disabled ? "disabled" : "",
      ].filter(Boolean).join(" ")}
      href={href}
      aria-label={label}
      aria-current={active ? "page" : undefined}
      aria-disabled={disabled || undefined}
      onClick={(event) => {
        event.preventDefault();
        if (!disabled) onClick();
      }}
    >
      {children}
    </a>
  );
}

function Chevron({ direction }: { direction: "left" | "right" }) {
  return (
    <svg className="ui-icon" viewBox="0 0 20 20" aria-hidden="true">
      <path d={direction === "left" ? "M12.5 4.5 7 10l5.5 5.5" : "M7.5 4.5 13 10l-5.5 5.5"} />
    </svg>
  );
}

function paginationItems(current: number, total: number): Array<number | "ellipsis"> {
  if (total <= 7) return Array.from({ length: total }, (_, index) => index + 1);
  const pages = new Set<number>([1, 2, total - 1, total, current - 1, current, current + 1]);
  if (current <= 4) {
    pages.add(2);
    pages.add(3);
    pages.add(4);
  }
  if (current >= total - 3) {
    pages.add(total - 1);
    pages.add(total - 2);
    pages.add(total - 3);
  }
  const sorted = Array.from(pages).filter((page) => page >= 1 && page <= total).sort((a, b) => a - b);
  return sorted.flatMap((page, index) => {
    const previous = sorted[index - 1];
    if (previous && page - previous > 1) return ["ellipsis" as const, page];
    return [page];
  });
}

function pageCountLabel(totalPages: number): string {
  return `${totalPages} ${totalPages === 1 ? "page" : "pages"}`;
}
