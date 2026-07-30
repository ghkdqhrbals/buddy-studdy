import { useMutation } from "@tanstack/react-query";
import { Send } from "lucide-react";
import { useMemo, useState } from "react";
import { adminFetch } from "../admin/adminApi.js";
import { Button } from "./Button.jsx";
import { InlineNotice } from "./InlineNotice.jsx";

const DESTINATIONS = [
  { value: "buddystudy://home/message", label: "Home popup" },
  { value: "buddystudy://home", label: "Home" },
  { value: "buddystudy://records", label: "Records" },
  { value: "buddystudy://statistics", label: "Statistics" },
  { value: "buddystudy://settings", label: "Settings" },
  { value: "buddystudy://public/questions", label: "Public questions" },
  { value: "__custom__", label: "Custom app deep link" },
];

export function AdminNotificationComposer({
  endpoint,
  title = "Send notification",
  description = "Queues an in-app notification and APNs push for this user.",
  initialTitle = "",
  initialBody = "",
  onSent,
}) {
  const [messageTitle, setMessageTitle] = useState(initialTitle);
  const [body, setBody] = useState(initialBody);
  const [destination, setDestination] = useState(DESTINATIONS[0].value);
  const [customDeepLink, setCustomDeepLink] = useState("");
  const deepLink = useMemo(
    () => destination === "__custom__" ? customDeepLink.trim() : destination,
    [customDeepLink, destination],
  );
  const mutation = useMutation({
    mutationFn: () => adminFetch(endpoint, {
      method: "POST",
      body: JSON.stringify({ title: messageTitle, body, deepLink }),
    }),
    onSuccess: (result) => {
      onSent?.(result);
    },
  });
  const canSend = messageTitle.trim().length > 0
    && body.trim().length > 0
    && deepLink.startsWith("buddystudy://");

  return (
    <section className="drawer-section">
      <h3>{title}</h3>
      <p className="section-description">{description}</p>
      <div className="form-grid">
        <label className="field">
          <span>Push title</span>
          <input
            maxLength="160"
            value={messageTitle}
            placeholder="A short notification title"
            onChange={(event) => setMessageTitle(event.target.value)}
          />
        </label>
        <label className="field">
          <span>Destination</span>
          <select value={destination} onChange={(event) => setDestination(event.target.value)}>
            {DESTINATIONS.map((item) => (
              <option key={item.value} value={item.value}>{item.label}</option>
            ))}
          </select>
        </label>
        {destination === "__custom__" ? (
          <label className="field">
            <span>Custom app deep link</span>
            <input
              value={customDeepLink}
              placeholder="buddystudy://records/123"
              onChange={(event) => setCustomDeepLink(event.target.value)}
            />
          </label>
        ) : null}
        <label className="field notification-message-field">
          <span>Message</span>
          <textarea
            maxLength="2000"
            value={body}
            placeholder="Markdown is supported in the in-app popup."
            onChange={(event) => setBody(event.target.value)}
          />
        </label>
      </div>
      <div className="drawer-form-actions">
        {mutation.isSuccess ? (
          <InlineNotice tone="success" compact>
            Notification queued · {mutation.data?.deepLink}
          </InlineNotice>
        ) : null}
        {mutation.error ? <InlineNotice tone="danger" compact>{mutation.error.message}</InlineNotice> : null}
        <Button
          icon={Send}
          busy={mutation.isPending}
          disabled={!canSend}
          onClick={() => mutation.mutate()}
        >
          Queue push
        </Button>
      </div>
    </section>
  );
}
