# 口袋大龙虾 Pocket Lobster 项目正式介绍 Project Overview
生成日期 Generated on: 2026-03-25

## 中文版本

### 一，项目是什么

口袋大龙虾是一款运行在普通 Android 手机上面的双智能体原生 AI 助手应用。它真正要做的事情，不是把聊天机器人搬到手机里，而是把两款原本主要运行在桌面系统、命令行终端或者云端环境中的顶级智能体，完整落到一台非 root 的安卓手机上，并且给它们配齐真实可执行的工作环境、系统级访问能力、全局文件处理能力以及长期可迭代的自进化链路。

这两款智能体分别是 Codex 和 OpenClaw。Codex 是 OpenAI 旗下的编码智能体，也是当前 AI 领域公认最强的软件与代码智能体之一，擅长理解代码库、修改工程、运行命令、调试问题、推进项目开发和自动完成大量技术任务。OpenClaw 则代表了 2026 年以来最具代表性的线上级私人 AI 智能体形态之一，它的意义在于把 AI 从传统聊天框里的问答工具，真正变成了具备工具调用、任务执行、环境操作、复杂流程编排和最终成果交付能力的全能助手。传统意义上的 OpenClaw 更偏向桌面电脑、命令行或云端运行，而口袋大龙虾的革命性意义就在于，它把 Codex 与 OpenClaw 这两款分别在各自领域都极具代表性的智能体，第一次真正完整地带到了一台普通安卓手机上。

### 二，口袋大龙虾最核心的四层能力

第一层能力是双智能体。口袋大龙虾不是单智能体应用，而是在同一个应用中同时集成了 Codex 与 OpenClaw 两套智能体系统。Codex 更偏向软件开发、工程维护、终端操作、代码理解与自动修改，OpenClaw 更偏向跨任务统筹、日常办公、内容创作、资料整理、复杂工具链调用以及面向普通用户的全能代理协作。两者并不是互相替代的关系，而是在一个应用中形成互补：一个更强于工程执行和编程生产力，另一个更强于广泛场景下的全能任务处理与代理式工作流。

第二层能力是双终端。传统的移动端 AI 应用，即便支持执行命令，通常也只会给智能体提供一套受限终端环境。口袋大龙虾把这一点直接向前推进了一大步：它在同一个应用中同时内置了两套不同的终端环境。第一套是安卓原生的 Termux 类型终端环境，用于直接访问应用运行时、本地命令行工具、应用私有目录和原生安卓环境；第二套是完整的 Ubuntu Linux 发行版运行时，用于提供真正意义上的 Linux 开发环境、包管理能力、编译能力、脚本能力以及更完整的工程操作空间。更关键的是，Codex 和 OpenClaw 这两款智能体都可以根据任务需要，自主选择进入安卓原生终端环境或者 Ubuntu Linux 终端环境工作，而这两套终端又可以通过手机共享存储目录共享文件、共享结果、共享任务产出，形成一套真正完整的移动端工作站。

第三层能力是系统权限链路。口袋大龙虾不仅有两套终端，还通过 Shizuku 授权打通了 system shell 这条系统级执行链路。它的意义非常关键，因为这意味着智能体不再只是被困在自己的应用沙箱里跑脚本，而是拥有了在非 root 前提下，直接读取系统信息、调试设备状态、维护系统设置、操作系统级命令，甚至进一步获得 UI 自动化操作能力的基础。通过这条链路，两个智能体都可以具备更接近真实数字助手和真实设备代理的能力，例如点击、滑动、输入文本、拉起应用、执行系统命令、读取界面信息等。正是因为双终端再加上一条 system shell 系统链路，口袋大龙虾才真正让 AI 在一台非 root 安卓手机上获得了尽可能完整的访问权限与执行能力。

第四层能力是全局文件与交付能力。很多同类型安卓项目最大的问题，是智能体最终只能在自己的应用私有沙箱里工作，文件产出、文档处理和任务交付都被困在应用内部，很难直接面向手机全局环境去处理真实文件。口袋大龙虾在这一点上做了关键突破：它申请并打通了共享存储的全局文件访问权限，也就是 Manage External Storage，这意味着两个智能体都不再局限于应用私有目录，而可以直接读取和处理手机共享存储中的文档、代码、音频、视频、安装包、图片以及其他用户文件，并且把最终结果直接交付回手机共享目录。对于手机用户来说，这种能力的意义非常大，因为 AI 处理完结果以后，产物不需要再从应用私有目录里艰难导出，而是可以直接在系统层面被用户继续使用、转发、编辑或归档。

### 三，这不只是开发工具，而是一台口袋里的通用 AI 工作站

很多人第一眼会把口袋大龙虾理解成移动端编程助手，但这其实只覆盖了它能力的一部分。只要两个智能体拥有了安卓原生终端、Ubuntu Linux 环境、共享存储、系统权限、网络检索和可扩展工具链，它们能够处理的事情就远远不止软件开发。

在日常办公场景中，它可以处理文档整理、批量文本处理、PDF 文件处理、表格转换、数据清洗、PPT 素材准备、知识归纳和资料整理。在多媒体场景中，它可以通过安装和调用例如 ffmpeg 这样的命令行工具，让一台手机直接变成全功能的音视频处理工作站，完成转码、裁剪、提取、混流、压缩、参数调整、格式转换和批量处理。在文件管理场景中，它可以直接对手机已有的文件进行检索、重命名、归档、转换与批处理。在技术场景中，它当然也可以完成代码编写、工程调试、构建 APK、发布迭代、云端仓库管理和自动化修复。

也就是说，口袋大龙虾的真正价值不在于“它能不能写代码”，而在于只要 AI 拥有足够多样化的工具环境和权限，它就可以在一台小小的手机上，完成原本需要桌面电脑、服务器甚至多套软件协作才能完成的大量任务。

### 四，强大的网络搜索、网页访问和扩展生态

口袋大龙虾并不把智能体限制在手机本地资源中。为了让两个智能体能获取外部信息、下载资源并执行更复杂的网络任务，当前环境中已经提供了完整的搜索与网页工具链。它支持多搜索源检索，包括百度搜索、夸克搜索、必应搜索、谷歌搜索、DuckDuckGo 搜索以及 Tavily 高级网络搜索；同时还支持网页访问、网络抓取和 Web 自动化桥接能力。这意味着两个智能体可以直接从互联网上检索信息、下载文档、获取音频视频、抓取网页内容、定位安装包、收集资料并把结果带回本地环境继续处理。

与此同时，口袋大龙虾也不是封闭系统。它兼容 OpenClaw 原有的 skills 生态，并且已经具备 MCP 服务接入与诊断能力，用户可以根据自己的工作、学习、创作和开发场景，持续安装和扩展更多工具能力。对于普通用户来说，这意味着口袋大龙虾不是一款只能做固定几件事情的应用，而是一套可随着需求不断扩展能力边界的移动端 AI 平台。

### 五，面向手机使用体验重新设计的大龙虾新版聊天页面

口袋大龙虾并不满足于简单沿用传统桌面版 OpenClaw 前端，而是专门围绕安卓手机的操作方式，对大龙虾聊天页面做了重新设计和简化，目的是把原本复杂、按钮密集、配置繁琐的桌面交互流程，重构成适合手机场景、适合无障碍使用、适合非技术用户操作的功能入口。

首先是权限管理中心。在这个入口里，用户可以直接查看和管理 Codex 授权状态、Codex 安装与修复状态，以及 Shizuku 授权和 system shell 通道状态，不再需要到终端里手动敲复杂命令。其次是提示词管理入口，用户可以自己新建提示词，把某条提示词映射为 Codex 当前开发过程中的系统提示词，也可以直接让智能体自己到环境里帮忙配置。然后是对话管理入口，这里可以统一管理 Codex 和 OpenClaw 两套智能体的会话内容，支持查看、编辑、删除、导出和继续进入会话。再往下是模型管理入口，这也是口袋大龙虾非常重要的体验改造之一，因为传统 OpenClaw 如果想新增或切换模型，往往要依赖终端命令，而在口袋大龙虾里，用户只需要填写模型 ID、Base URL 和 API 密钥三个文本框，就可以直接创建、保存、编辑、删除和切换模型，甚至也可以把模型信息直接发给两个智能体中的任意一个，让 AI 代为自动配置和测试连通性。

另外，新版大龙虾聊天页面还专门加入了附件能力，支持拍照、从相册选择、从文件选择，把图片或文件实时上传给智能体处理；如果用户不想手动上传，也可以直接把手机上的文件路径交给智能体，让它自己去找文件并继续处理。这些设计共同构成了口袋大龙虾在手机端最核心的用户体验优势：它不是把桌面操作硬塞进手机，而是把复杂的 AI 能力尽可能转化成手机上真正可用、可操作、可交付的工作流入口。

### 六，为什么它和其他同类型安卓项目不一样

口袋大龙虾真正拉开差距的地方，不是“也能在安卓上跑 OpenClaw”，而是把许多同类型项目长期做不到或者没有彻底打通的关键能力，组合成了一条完整链路。

第一，它不是只有单智能体，而是双智能体协作。第二，它不是只有一套终端，而是同时提供安卓原生终端和完整 Ubuntu Linux 终端。第三，它不是只有应用内部命令执行，而是通过 Shizuku 授权给智能体提供了 system shell 与 UI 自动化能力。第四，它不是把文件困在应用私有目录，而是拿到了共享存储的全局文件访问能力。第五，它不是纯本地封闭环境，而是提供了多搜索引擎、网页访问和网络自动化能力。第六，它不是只适合技术用户，而是把模型管理、权限管理、提示词管理、对话管理、附件上传这些原本必须靠终端完成的高门槛操作，尽量压缩成了手机端可直接使用的功能入口。

从实际意义上说，口袋大龙虾目前已经接近把一台普通安卓手机，变成一台随身可携带、可联网、可开发、可处理文档、可处理多媒体、可执行复杂任务、可直接交付结果的 AI 工作站。

### 七，口袋大龙虾已经具备自进化能力

口袋大龙虾最独特也最有未来意义的一点，是它已经不再只是一个被动等待开发者维护的应用。只要给两个智能体中的任意一个配置好 GitHub 账户令牌，或者在环境中准备好安卓开发所需的相关工具链，它们就可以直接根据用户需求，或者根据自己完成任务时遇到的问题，去修改代码、产出新版本安装包、触发云端工作流构建、下载产物、安装新版本，甚至在用户和智能体一起测试以后继续更新迭代。

换句话说，口袋大龙虾本身已经具备“让 AI 参与开发和演进自己所运行的宿主环境”的能力。这并不是一句宣传口号，而是已经被真实验证过的工作方式。当前这个稳定版本，正是通过一位完全不懂编程的视障用户，借助智能体持续交互、持续验证、持续修复、持续构建的方式，一步一步演进到今天这个状态的。这也是口袋大龙虾与传统软件最大的不同之一：它不是静态的软件产品，而是一个能够随着用户需求、环境条件和智能体能力共同增长的移动端 AI 演化系统。

### 八，项目的真正意义

口袋大龙虾的意义不只是“把两个 AI 装进手机”，而是证明了一件更大的事情：只要 AI 拥有足够真实的工具环境、足够广泛的权限边界、足够灵活的终端能力和足够完整的交付链路，那么哪怕是在一台小小的安卓手机上，也同样可以产生接近桌面系统甚至在某些场景下超越桌面系统的工作能力。

它让一台手机不再只是消费内容的设备，而有机会变成真正的生产终端、开发终端、创作终端和个人 AI 工作平台。对于普通用户来说，它意味着 AI 不再只是回答问题，而是可以代为做事；对于技术用户来说，它意味着移动端终于拥有了真正可演进的智能体基础设施；对于依赖无障碍能力和自然语言驱动的人来说，它意味着在不依赖大量手动操作、不依赖编程基础的前提下，也可以借助 AI 完成高质量、高复杂度、跨领域的真实工作。

这就是口袋大龙虾截至目前最核心的价值：它把两款分别在编码智能体和全能智能体方向上极具代表性的系统，连同双终端、系统权限、全局文件访问、网络搜索、工具扩展和自进化能力，一起压缩进了一台普通安卓手机里，并让它们真正开始为现实任务交付成果。

## English Version

### 1. What the project is

Pocket Lobster is a dual-agent native AI assistant designed to run on an ordinary Android phone. Its goal is not to squeeze a chatbot into a mobile app. Its real goal is to bring two top-tier agents, which traditionally live on desktop operating systems, command-line terminals, or cloud machines, onto a non-root Android phone with real working environments, system-level access, global file handling, and a long-term path for self-evolution.

The two agents are Codex and OpenClaw. Codex is OpenAI's coding agent and is widely recognized as one of the strongest software and code agents in the field. It excels at understanding repositories, editing code, running commands, debugging issues, advancing software projects, and automating technical work. OpenClaw represents one of the most important online-grade personal AI agent forms since 2026. It turns AI from a question-and-answer tool inside a chat box into a general-purpose assistant that can call tools, execute tasks, manipulate environments, orchestrate complex flows, and deliver final results. Traditional OpenClaw usage is oriented toward desktops, terminals, or cloud environments. The revolutionary value of Pocket Lobster is that it brings both Codex and OpenClaw, two highly representative agents in their respective domains, onto an ordinary Android phone in a truly complete way.

### 2. The four foundational capability layers

The first layer is dual agents. Pocket Lobster is not a single-agent app. It integrates both Codex and OpenClaw inside the same application. Codex is better suited for software development, engineering maintenance, terminal operations, code understanding, and automated modification. OpenClaw is better suited for broad task coordination, everyday office work, content creation, research and information organization, complex toolchain invocation, and general-purpose agent collaboration for ordinary users. They do not replace one another. They complement one another: one is stronger in engineering execution and coding productivity, while the other is stronger in broad-spectrum task execution and agentic workflows.

The second layer is dual terminals. Most mobile AI apps that support command execution only offer one constrained shell. Pocket Lobster pushes well beyond that limitation by embedding two separate terminal environments into the same app. The first is an Android-native, Termux-like terminal used for app runtime access, local command-line tools, app-private directories, and the native Android environment. The second is a full Ubuntu Linux runtime that provides a true Linux development environment, package management, compilation, scripting capability, and a much more complete engineering workspace. More importantly, both Codex and OpenClaw can decide which environment to use depending on the task. The two terminal environments can also exchange files and outputs through Android shared storage, turning the phone into a complete mobile workstation.

The third layer is the system-permission path. Pocket Lobster does not stop at giving agents two terminals. It also opens a system-level execution path through a Shizuku-backed system shell. This matters because it means the agents are no longer trapped inside an application sandbox. Without root, they can still inspect system information, diagnose device state, maintain settings, run system-level commands, and form the basis for UI automation. Through this path, both agents can move closer to being real device-level assistants, with capabilities such as tapping, swiping, entering text, launching apps, executing system commands, and reading interface state. Dual terminals plus system shell is what gives AI near-maximal non-root execution power on Android.

The fourth layer is global file access and delivery. Many Android AI projects fail at the point where the agent can only work inside the app's private sandbox. Files, documents, and deliverables get trapped inside internal storage. Pocket Lobster solves this by obtaining shared-storage global file access through Manage External Storage. That means both agents can directly read and process documents, code, audio, video, APKs, images, and other user files from shared storage, then write the final result back to a place the user can immediately use. This is a key transition from AI being able to run on the phone to AI being able to complete work on the phone and deliver the result.

### 3. More than a coding tool: a general AI workstation in your pocket

Many people will first understand Pocket Lobster as a mobile programming assistant, but that only covers part of its value. Once two agents have an Android-native terminal, a Ubuntu Linux runtime, shared storage, system permissions, network retrieval, and extensible tools, the set of tasks they can handle goes far beyond software development.

In everyday office work, it can handle document organization, bulk text processing, PDF workflows, table conversion, data cleaning, presentation preparation, knowledge synthesis, and material organization. In multimedia workflows, it can install and call tools such as ffmpeg so that a phone becomes a full audio and video processing workstation for transcoding, trimming, extraction, muxing, compression, parameter tuning, format conversion, and batch processing. In file-management workflows, it can search, rename, archive, convert, and batch-process existing files on the phone. In technical workflows, it can write code, debug projects, build APKs, manage releases, maintain cloud repositories, and automate repairs.

In other words, the real value of Pocket Lobster is not whether it can write code. Its real value is that once AI has enough execution environments and permissions, a small phone can complete a large amount of work that used to require a desktop computer, a server, or several separate tools working together.

### 4. Search, web access, extensibility, and mobile-first interaction

Pocket Lobster does not confine its agents to local phone resources. The current environment provides a complete search and web tooling chain, including Baidu, Quark, Bing, Google, DuckDuckGo, and Tavily, together with web access, scraping, and web automation bridges. This lets both agents search the public internet, download documents, retrieve audio and video, inspect pages, locate packages, gather reference material, and bring the results back into the local environment for further processing.

At the same time, Pocket Lobster is not a closed system. It remains compatible with the OpenClaw skills ecosystem and already has MCP service access and diagnostic capability, which means users can keep expanding it for work, study, creative production, and development. For ordinary users, that means Pocket Lobster is not a fixed-function app. It is a mobile AI platform whose capabilities can continue to expand.

Pocket Lobster also redesigns the interaction layer for real phone use rather than simply reusing a dense desktop-style OpenClaw frontend. The new Lobster chat page simplifies complex desktop configuration flows into phone-friendly, accessibility-friendly entry points. These include a permission management center, prompt profile management, conversation management, model management, and file attachment support. Users can inspect Codex authorization, Codex installation and repair status, Shizuku authorization, and system shell status without dropping into a terminal. They can create prompt profiles, map one of them to the current Codex workflow, manage both Codex and OpenClaw conversations, create and switch models through a form-based model manager, and attach files by camera, gallery, or file chooser. Just as importantly, they can also hand those tasks to either agent and let the AI configure the environment for them.

### 5. Why it is different and why self-evolution matters

What truly differentiates Pocket Lobster from other Android OpenClaw-style projects is not simply that it can also run OpenClaw on Android. Its real difference is that it connects several capabilities that similar projects usually leave fragmented or incomplete. It combines dual agents, dual terminals, a Shizuku-based system shell path, UI automation foundations, global shared-storage file access, multi-engine web retrieval, and phone-native management interfaces. Instead of confining results to app-private storage or forcing advanced users back into a terminal for every serious operation, it turns those high-friction steps into capabilities that both the user and the agents can access directly.

Most importantly, Pocket Lobster has already demonstrated self-evolution. If either agent is given a GitHub token or a prepared Android development toolchain, it can modify its own source code, trigger cloud workflows, build a new APK, download the output, install the next version, and continue testing and iterating with the user. This is not a slogan. It is already the verified way the project has been developed. The current stable version was advanced step by step through long-term collaboration between the agents and a visually impaired user who does not write code, using continuous dialogue, verification, repair, and rebuilding. That makes Pocket Lobster fundamentally different from a static software product. It is a mobile AI evolution system that can grow together with user needs, environment constraints, and agent capability.

### 6. The broader meaning of the project

The deeper meaning of Pocket Lobster is not merely that it puts two AI systems into a phone. It proves something larger: once AI has sufficiently real tools, sufficiently broad permissions, sufficiently flexible terminal environments, and a complete delivery chain, then even a small Android phone can produce work that approaches desktop-class capability, and in some situations can even exceed what a traditional desktop workflow can achieve.

It turns a phone from a content-consumption device into a production terminal, development terminal, creative terminal, and personal AI workstation. For ordinary users, it means AI is no longer limited to answering questions and can actually do work on their behalf. For technical users, it means mobile devices can finally host a genuinely evolvable agent infrastructure. For people who rely on accessibility and natural-language-driven operation, it means high-quality, high-complexity, cross-domain work can be completed without heavy manual interaction or programming skills.

That is the core value of Pocket Lobster at its current stage: it compresses two highly representative agent systems, one from the coding-agent direction and one from the general-purpose agent direction, together with dual terminals, system permissions, global file access, web retrieval, extensibility, and self-evolution, into an ordinary Android phone and uses them to deliver results for real-world tasks.
