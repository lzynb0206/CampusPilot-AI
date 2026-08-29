# 海报模板素材放置说明

请把校园活动海报的版式参考图片放在当前目录：

`src/main/resources/poster-templates/references/`

这些图片只用于总结标题区、校徽区和信息区等布局规则，不会作为运行时固定背景。
AI 每次都会独立生成新的无字背景，Java 程序再按布局规则叠加准确文字。

可以继续准备以下类型的参考图：

- `general-01.png`：通用校园活动
- `technology-01.png`：技术分享、AI、编程活动
- `sports-01.png`：运动、比赛、户外活动
- `performance-01.png`：晚会、音乐、舞蹈、演出

当前已经整理好的版式参考：

- `performance-01-layout-reference.png`：居中大标题、左上品牌区、底部信息区。
- `performance-02-layout-reference.png`：上方品牌区、中上标题区、底部信息卡。

素材要求：

- 优先使用 PNG 和接近 3:4 的竖版构图；最终渲染阶段统一适配到海报输出尺寸。
- 参考图可以带示例文字，但不能把其中的信息当成新活动的真实内容。
- 重点是让布局关系清楚：顶部品牌区、中上标题区、底部日期/时间/地点区。
- 四周至少保留 80 像素安全边距，重要装饰不要贴边。
- 不要放真实二维码、校徽、品牌 Logo、水印或联系方式。
- 同一类别可以继续添加 `technology-02.png`、`technology-03.png` 等。

建议使用 `-layout-reference` 后缀，例如 `technology-01-layout-reference.png`。

真正的学校校徽请放在相邻的 `poster-templates/logos/` 目录，不要从参考海报中自动裁切，
以免误用院系、社团或主办方标志。
