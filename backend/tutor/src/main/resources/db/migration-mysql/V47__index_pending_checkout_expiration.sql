alter table invoices
    add index idx_invoices_checkout_expiration (type, status, created_at, id);
