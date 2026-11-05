package com.cordiact.app.render

/**
 * Sample content for the preview window's dev action.
 *
 * It is deliberately a little broken — a 40dp button, a low-contrast caption and a row that
 * overflows its card — so that pressing 检查 in the window produces findings instead of an
 * empty list. If this ever renders clean, either the checks or the CSS regressed.
 */
internal object PreviewSamples {

    val brokenCss = """
        :root { color-scheme: light; }
        html, body { margin: 0; background: #ffffff; }
        body { padding: 16px; font-family: system-ui, sans-serif; color: #111111; }
        .card {
          border-radius: 16px; padding: 16px; box-sizing: border-box;
          background: linear-gradient(140deg, #eef2ff, #ffffff 60%);
          border: 1px solid #e5e7eb;
        }
        .title { font-size: 24px; font-weight: 700; margin: 0 0 6px; }
        .caption { color: #a8a8a8; font-size: 13px; margin: 0 0 16px; }
        .row { display: flex; gap: 8px; width: 720px; }
        .pill {
          display: inline-block; padding: 6px 12px; border-radius: 999px;
          background: #111111; color: #ffffff; font-size: 12px;
        }
        .cta {
          width: 40px; height: 40px; border-radius: 50%; border: 0;
          background: #4f46e5; color: #fff; font-size: 18px;
        }
        .bounce { animation: bounce 1.2s ease-in-out infinite; }
        @keyframes bounce {
          0%, 100% { transform: translateY(0); }
          50%      { transform: translateY(-10px); }
        }
    """.trimIndent()

    val brokenHtml = """
        <div class="card">
          <p class="title">本周回顾</p>
          <p class="caption">自动生成 · 点击查看详情</p>
          <div class="row">
            <span class="pill">12 次提交</span>
            <span class="pill">3 个发布</span>
            <span class="pill">1 次回滚</span>
            <span class="pill">7 个新任务</span>
            <span class="pill">2 个阻塞项</span>
          </div>
          <div style="height:16px"></div>
          <button class="cta bounce">→</button>
        </div>
    """.trimIndent()
}
