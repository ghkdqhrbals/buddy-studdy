# Korean iPhone Education Top 10 visual benchmark

## Snapshot and method

- Chart: [Apple Korea · iPhone · Education · Top Free](https://apps.apple.com/kr/iphone/charts/6017?chart=top-free).
- Chart HTML saved: **2026-09-28 06:28:13 KST** (2026-09-27 21:28:13 UTC); screenshot retrieval finished at 06:29:23 KST. Rankings are this regional chart snapshot, not permanent rankings or evidence that screenshot design causes rank.
- Read the live official chart HTML ordinal labels, then each listed app’s Korean App Store `product_media_phone_` screenshot gallery. Downloaded the **first three official iPhone screenshots for each of all 10 apps** and visually inspected 30 images in 10 contact sheets. The same 10 apps appeared in the search/browser cache, but positions 3–5 differed; this table uses the direct live chart snapshot.
- Headline lengths and visible UI height below are visual estimates, not pixel/text OCR measurements. “UI” includes the visible native-screen region within device framing; cropped devices may extend beyond the canvas.
- Competitor images are research only, stored outside the repository in `/tmp/buddystudy-top10-benchmark/`. `evidence.json` records each official Apple image URL and source dimensions; `contact-01.jpg` through `contact-10.jpg` are the inspected contact sheets. No competitor assets are included in BuddyStudy production artwork.

## All 10 apps inspected

| Rank | Official app page | Headline treatment | Palette and UI scale | First three screenshot sequence |
| --- | --- | --- | --- | --- |
| 1 | [오르조](https://apps.apple.com/kr/app/id1529046013) | Large navy Korean benefit statements, generally two short lines; small secondary proof labels. | Pale blue/cyan gradient. First phone about 60% of height; second uses a content-cover grid; third phone/chat about 70%. | Outcome plus social proof → breadth of exam content → AI learning helper. |
| 2 | [듀오링고](https://apps.apple.com/kr/app/id570060128) | Bold white two or three lines; one lesson benefit per image. | Orange, cyan, lilac panels. Large white native lesson surface about 70–75% of height, cropped at bottom. | Language breadth → visual vocabulary exercise → sentence-building exercise. |
| 3 | [ENGSENCE](https://apps.apple.com/kr/app/id6754227000) | No added external marketing headline. Small native English onboarding text and coachmarks. | Full-frame native UI, navy/cream. Screen content covers the full canvas. | Daily expression popup → notification onboarding step → video/volume onboarding step. |
| 4 | [열품타](https://apps.apple.com/kr/app/id1441909643) | Black, bold, two-line benefit headlines; first has a short subtitle. | White with orange UI accents. First angled multi-phone montage about 65–70%; next two upright phones about 75%. | Study-product overview → personal planning → studying alongside others. |
| 5 | [찰칵](https://apps.apple.com/kr/app/id6747833381) | Very large two-line black headline with a purple/blue highlighted phrase; small gray supporting sentence. | White canvas, purple/blue accent, large black phone frame. Native proof about 70–75% of height. | Capture a math problem → worked explanation → character/personality distinction. |
| 6 | [말해보카](https://apps.apple.com/kr/app/id1460766549) | Large white two-line motivation/benefit copy; third panel switches to a data argument. | Purple gradient; big tilted phones crossing panel edges in first two, about 55–65% height. Third is an illustrated chart without app UI. | Learning motivation → repeatable learning habit → explanation of a learning gap. |
| 7 | [Widget Vocabulary](https://apps.apple.com/kr/app/id6775767149) | White/ivory two-line headlines with a short benefit in each. | Dark navy star background and gold highlights. Dominant phone about 70–75% of height. | Lock-screen learning → personalizable widgets → word-learning library. |
| 8 | [족보닷컴](https://apps.apple.com/kr/app/id6473707145) | First brand/context image; second large centered three-line promise; third two-line benefit with blue emphasis. | First photographic gray overlay, second saturated blue, third white/blue. No app UI in first two; partial phone and proof card about 55–60% in third. | Student aspiration → school relevance → customized practice creation. |
| 9 | [편입로그](https://apps.apple.com/kr/app/id6801908178) | White bold two-line benefit plus a short support sentence. | Navy, blue, violet canvases; stable large phone composition about 75–80% of height. | All-in-one overview → compare schools → English practice. |
| 10 | [LearnKata](https://apps.apple.com/kr/app/id6792461301) | Black bold one or two lines, blue emphasis; first has an outcome claim and testimonial. | White/pale blue. First illustration without app UI; second smaller phone about 50%; third learning path about 60%. | Claimed outcome/social proof → bring in study material → learning path. |

## What BuddyStudy should borrow

Nine of these ten galleries use added marketing headlines instead of relying on a raw screen to explain itself. The common useful pattern is **one benefit per frame, a short high-contrast heading, and one large piece of native product proof**. Roughly two lines, rather than a paragraph or feature checklist, remain readable in a small App Store carousel. Most phone-led examples allocate roughly 55–80% of the canvas height to the product.

The opening sequence commonly establishes a reason to care, then shows how learning works, then reinforces the value with another concrete capability. It is not a tour of tab labels. Across the gallery a controlled palette and repeated type/device geometry make the screenshots recognizable as one product. A few intentional background changes give rhythm.

Do not borrow competitor mascots, testimonials, download/rank badges, unsupported learning-speed claims, or visual assets. Their use of those claims is not evidence that BuddyStudy can make them. This benchmark also does not imply Apple’s ranking algorithm can be directly configured or that a Top 10 placement is guaranteed.

## Proposed BuddyStudy six-panel story

| Panel | Korean headline (line break shown with /) | Supporting copy | Native proof |
| --- | --- | --- | --- |
| 1 | 질문 하나로, / 내 이해를 확인 | 짧게 답하고 AI 피드백으로 돌아봐요 | Completed question, answer and AI feedback (`learning-result`). |
| 2 | 내 답변에서 / 한 걸음 더 깊게 | AI 꼬리질문으로 생각을 이어가요 | Actual follow-up thread (`follow-up`). No claim of unlimited/free generation. |
| 3 | 함께 읽을 질문, / 관심주제로 발견 | 공개 질문을 검색하고 관심주제를 구독해요 | Current public Home/feed, search and interests. No removed recommendation/views sort controls. |
| 4 | 관심을 넓히는 / 나만의 학습 트리 | 큰 주제에서 세부 주제로 넓혀가요 | Actual My Studies tree with circular saved nodes. |
| 5 | 주제별로 보는 / 나의 성장 | 학습 기록으로 이해와 성장 흐름을 확인해요 | Topic-first statistics (`statistics`). No global average/ranking claim. |
| 6 | 내 질문도, / 내 공부 기록으로 | 직접 쓴 질문과 답변을 채점 없이 저장해요 | Custom question/answer editor or saved custom record (`custom-question` / `custom-records`). Keep private/skip-grading behavior true. |

Use BuddyStudy’s existing green/blue/yellow/ink identity with an off-white canvas and a few strong dark/blue panels. Added headlines are marketing copy, while every native screen must come from the integrated 1.3.0 candidate. Use the generated three-ring learning-tree accent sparingly as decoration, never as fabricated UI. Keep the actual screen large, straight, legible, and unmodified inside restrained device framing.

All three supported localizations need independently fitted line breaks; do not shrink long English/Japanese copy until unreadable. Tablet output must use actual tablet captures and tablet framing. Existing 1.2.0 screenshots may be used only for a clearly separated renderer prototype, never uploaded as current 1.3.0 product proof.
