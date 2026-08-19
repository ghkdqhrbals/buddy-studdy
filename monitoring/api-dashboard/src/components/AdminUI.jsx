import {
  ChevronLeft,
  ChevronRight,
  Database,
  RefreshCw,
  Search,
  X,
} from "lucide-react";
import { useState } from "react";
import { Button } from "./Button.jsx";

export function PageHeader({ eyebrow, title, description, actions }) {
  return (
    <header className="page-header">
      <div>
        {eyebrow ? <span className="page-eyebrow">{eyebrow}</span> : null}
        <h1>{title}</h1>
        {description ? <p>{description}</p> : null}
      </div>
      {actions ? <div className="page-actions">{actions}</div> : null}
    </header>
  );
}

export function SearchField({
  value,
  onChange,
  onSubmit,
  placeholder,
  label = "Search",
  className = "",
}) {
  return (
    <form
      className={`search-field ${className}`.trim()}
      role="search"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit?.();
      }}
    >
      <Search size={17} aria-hidden="true" />
      <label className="sr-only" htmlFor={`search-${label.replaceAll(" ", "-")}`}>{label}</label>
      <input
        id={`search-${label.replaceAll(" ", "-")}`}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
      />
      {value ? (
        <button type="button" className="clear-input" onClick={() => onChange("")} aria-label="Clear search">
          <X size={15} />
        </button>
      ) : null}
      <Button type="submit" variant="secondary" icon={Search}>Search</Button>
    </form>
  );
}

export function DataTable({ columns, rows, rowKey, onRowClick, emptyText, loading }) {
  const initialLoading = loading && rows.length === 0;
  return (
    <div className="table-frame">
      <table className="data-table">
        <thead>
          <tr>{columns.map((column) => <th key={column.key}>{column.label}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={rowKey(row)}
              className={onRowClick ? "clickable-row" : ""}
              tabIndex={onRowClick ? 0 : undefined}
              onClick={() => onRowClick?.(row)}
              onKeyDown={(event) => {
                if (!onRowClick || !["Enter", " "].includes(event.key)) return;
                event.preventDefault();
                onRowClick(row);
              }}
            >
              {columns.map((column) => (
                <td key={column.key} className={column.className || ""}>
                  {column.render ? column.render(row) : row[column.key] ?? "-"}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {initialLoading ? <div className="table-state"><RefreshCw className="spin" size={18} /> Loading data...</div> : null}
      {!initialLoading && rows.length === 0 ? (
        <div className="table-state"><Database size={18} /> {emptyText}</div>
      ) : null}
    </div>
  );
}

export function Pagination({ page, totalPages, onPrevious, onNext, hasNext, label }) {
  return (
    <div className="pagination">
      <span>{label}</span>
      <div>
        <Button
          variant="ghost"
          icon={ChevronLeft}
          onClick={onPrevious}
          disabled={page <= 1}
        >
          Previous
        </Button>
        <strong>Page {page}{totalPages ? ` of ${totalPages}` : ""}</strong>
        <Button
          variant="ghost"
          icon={ChevronRight}
          onClick={onNext}
          disabled={hasNext === false || (totalPages && page >= totalPages)}
        >
          Next
        </Button>
      </div>
    </div>
  );
}

export function SegmentedTabs({ value, onChange, items, ariaLabel }) {
  return (
    <div className="segmented-tabs" role="tablist" aria-label={ariaLabel}>
      {items.map((item) => (
        <button
          type="button"
          role="tab"
          aria-selected={value === item.value}
          key={item.value}
          onClick={() => onChange(item.value)}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}

export function StatusBadge({ children, tone = "neutral" }) {
  return <span className="status-badge" data-tone={tone}>{children ?? "-"}</span>;
}

export function ExpandableText({ value, label = "details", className = "" }) {
  const [expanded, setExpanded] = useState(false);
  if (!value) return <span>-</span>;
  return (
    <button
      type="button"
      className={`expandable-text ${className}`.trim()}
      data-expanded={expanded ? "true" : "false"}
      aria-expanded={expanded}
      aria-label={`${expanded ? "Collapse" : "Show full"} ${label}`}
      title={value}
      onClick={(event) => {
        event.stopPropagation();
        setExpanded((current) => !current);
      }}
      onKeyDown={(event) => event.stopPropagation()}
    >
      <span>{value}</span>
      <small>{expanded ? "Show less" : "Show full"}</small>
    </button>
  );
}

export function DetailDrawer({ open, title, subtitle, onClose, children, width = "wide" }) {
  if (!open) return null;
  return (
    <div className="drawer-layer">
      <button className="drawer-backdrop" type="button" onClick={onClose} aria-label="Close details" />
      <aside className="detail-drawer" data-width={width} aria-label={title}>
        <header>
          <div>
            <span className="page-eyebrow">Object details</span>
            <h2>{title}</h2>
            {subtitle ? <p>{subtitle}</p> : null}
          </div>
          <button className="icon-button" type="button" onClick={onClose} aria-label="Close details">
            <X size={19} />
          </button>
        </header>
        <div className="drawer-content">{children}</div>
      </aside>
    </div>
  );
}
