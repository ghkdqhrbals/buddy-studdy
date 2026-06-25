import { useState } from "react";
import type { MouseEvent, ReactNode } from "react";
import { paginationItems } from "./paginationModel";

type PaginationProps = {
  start: number;
  end: number;
  totalCount: number;
  currentPage: number;
  totalPages: number;
  pageSize: number;
  hrefForPage: (page: number) => string;
  onPageChange: (page: number) => void;
};

export function Pagination({
  start,
  end,
  totalCount,
  currentPage,
  totalPages,
  pageSize,
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
        <b>Page {currentPage} of {totalPages}</b>
        {" "}
        <span aria-hidden="true">·</span>
        {" "}
        <span>{start}-{end} of {totalCount}</span>
        {" "}
        <span aria-hidden="true">·</span>
        {" "}
        <span>{pageSize} rows/page</span>
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
          typeof item === "object" ? (
            <PageAnchor
              key={`ellipsis-${index}-${item.page}`}
              href={hrefForPage(item.page)}
              label={`Jump to page ${item.page}`}
              title={`Jump to page ${item.page}`}
              variant="ellipsis"
              onClick={() => onPageChange(item.page)}
            >
              ...
            </PageAnchor>
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
          <form
            className="page-jump"
            onSubmit={(event) => {
              event.preventDefault();
              submitJump();
            }}
          >
            <label htmlFor="admin-page-jump">Page</label>
            <input
              id="admin-page-jump"
              inputMode="numeric"
              pattern="[0-9]*"
              min={1}
              max={totalPages}
              value={jumpPage}
              placeholder={String(currentPage)}
              aria-label="Jump to page"
              onChange={(event) => setJumpPage(event.target.value)}
            />
            <button type="submit" className="page-jump-button">Go</button>
          </form>
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
  title,
  variant,
  onClick,
  children,
}: {
  active?: boolean;
  disabled?: boolean;
  href: string;
  label?: string;
  title?: string;
  variant?: "ellipsis";
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <a
      className={[
        "page-button",
        label ? "icon-page" : "",
        variant === "ellipsis" ? "page-ellipsis" : "",
        active ? "active" : "",
        disabled ? "disabled" : "",
      ].filter(Boolean).join(" ")}
      href={href}
      aria-label={label}
      title={title}
      aria-current={active ? "page" : undefined}
      aria-disabled={disabled || undefined}
      onClick={(event) => {
        if (disabled) {
          event.preventDefault();
          return;
        }
        if (!shouldHandleClientNavigation(event)) return;
        event.preventDefault();
        onClick();
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

function shouldHandleClientNavigation(event: MouseEvent<HTMLAnchorElement>): boolean {
  return event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;
}
