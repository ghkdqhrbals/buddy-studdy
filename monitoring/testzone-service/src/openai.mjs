const SYSTEM_PROMPT = `You are the TestZone k6 script assistant.
Write secure, deterministic k6 JavaScript for API performance tests.
The script must:
- import only k6 built-in modules
- use __ENV.BASE_URL, __ENV.HEADERS_JSON, __ENV.VUS, __ENV.MAX_VUS, __ENV.TARGET_RPS, and __ENV.DURATION
- support constant-arrival-rate when TARGET_RPS is greater than zero and VU mode otherwise
- tag every request with a stable api tag
- validate status and required response fields with check()
- never embed credentials, tokens, or fixed production secrets
- keep max VUs configurable through __ENV.MAX_VUS
Return a concise explanation and the complete executable script.`;

function extractOutputText(response) {
  if (typeof response.output_text === "string") return response.output_text;
  for (const item of response.output || []) {
    for (const content of item.content || []) {
      if (content.type === "output_text" && typeof content.text === "string") return content.text;
    }
  }
  throw new Error("OpenAI returned no text output.");
}

export function parseAssistantResult(response) {
  const text = extractOutputText(response);
  const parsed = JSON.parse(text);
  if (!parsed.message || !parsed.code) throw new Error("OpenAI response omitted message or code.");
  return { message: parsed.message, code: parsed.code };
}

export class K6Assistant {
  constructor(config, fetchImpl = fetch) {
    this.config = config;
    this.fetch = fetchImpl;
  }

  get enabled() {
    return Boolean(this.config.apiKey);
  }

  async generate(input) {
    if (!this.enabled) throw new Error("TestZone OpenAI API key is not configured.");
    const response = await this.fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: this.config.model,
        store: false,
        input: [
          { role: "system", content: [{ type: "input_text", text: SYSTEM_PROMPT }] },
          {
            role: "user",
            content: [{
              type: "input_text",
              text: JSON.stringify({
                request: input.prompt,
                project: { name: input.projectName, baseUrl: input.baseUrl },
                currentScript: input.currentCode || "",
              }),
            }],
          },
        ],
        text: {
          format: {
            type: "json_schema",
            name: "k6_script_response",
            strict: true,
            schema: {
              type: "object",
              additionalProperties: false,
              properties: {
                message: { type: "string" },
                code: { type: "string" },
              },
              required: ["message", "code"],
            },
          },
        },
      }),
    });
    if (!response.ok) {
      const detail = await response.text();
      throw new Error(`OpenAI request failed (${response.status}): ${detail.slice(0, 500)}`);
    }
    return parseAssistantResult(await response.json());
  }
}
