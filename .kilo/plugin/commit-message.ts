/**
 * Commit Message CLI 插件
 *
 * 功能：
 * - system prompt 指令：引导 AI 生成 commit message 后交互式确认
 * - commit-message 工具：按指定文件执行 git commit
 *
 * 放置位置: .kilo/plugin/commit-message.ts
 */

import type { Plugin } from "@kilocode/plugin"
import { tool } from "@kilocode/plugin/tool"

const CommitMessagePlugin: Plugin = async ({ $ }) => {
  return {
    "experimental.chat.system.transform": async (_input, output) => {
      output.system.push(
        "## 提交信息生成规则\n" +
          "当用户发送代码变更内容并要求生成提交信息时：\n" +
          "1. 分析变更内容，生成符合 Conventional Commits 规范的提交信息\n" +
          "2. 使用 question 工具询问用户是否确认提交（选项：确认提交 / 修改）\n" +
          "3. 用户确认后，调用 commit-message 工具（action=commit）执行提交\n" +
          "   重要：必须传入 files 参数，只提交用户指定的文件，不要提交其他暂存文件\n" +
          "4. 用户选择修改时，根据反馈重新生成\n",
      )
    },

    tool: {
      "commit-message": tool({
        description:
          "分析 Git 暂存变更或执行提交。\n" +
          "action=diff: 获取暂存变更内容\n" +
          "action=commit: 指定文件和提交信息执行 git commit\n" +
          "重要：commit 时必须传入 files 参数，只提交指定文件",
        args: {
          action: tool.schema
            .enum(["diff", "commit"])
            .describe("diff=获取变更内容，commit=执行提交")
            .default("diff"),
          message: tool.schema
            .string()
            .optional()
            .describe("当 action=commit 时，指定提交信息"),
          files: tool.schema
            .array(tool.schema.string())
            .optional()
            .describe("当 action=commit 时，指定要提交的文件路径列表。只提交这些文件，不提交其他暂存文件"),
        },
        async execute(args, ctx) {
          const cwd = ctx.directory

          if (args.action === "commit") {
            if (!args.message) {
              return { title: "错误", output: "提交时必须提供 message 参数" }
            }

            const safeMsg = args.message.replace(/"/g, '\\"')
            const files = args.files || []

            let result
            if (files.length > 0) {
              // 只提交指定文件: git commit -m "msg" -- file1 file2
              result = await $`git commit -m ${safeMsg} -- ${files}`.cwd(cwd).nothrow()
            } else {
              result = await $`git commit -m ${safeMsg}`.cwd(cwd).nothrow()
            }

            if (result.exitCode === 0) {
              const hash = await $`git rev-parse --short HEAD`.cwd(cwd).text()
              const fileCount = files.length > 0 ? files.length : "all staged"
              return {
                title: "提交成功",
                output: `已提交: ${args.message}\nCommit: ${hash.trim()}\n文件数: ${fileCount}`,
              }
            }
            const err = await result.text()
            return {
              title: "提交失败",
              output: `git commit 失败 (exit ${result.exitCode}):\n${err}`,
            }
          }

          // action === "diff"
          const staged = await $`git diff --cached --name-status`.cwd(cwd).text()
          if (!staged.trim()) {
            const unstaged = await $`git diff --name-status`.cwd(cwd).text()
            if (unstaged.trim()) {
              return {
                title: "无暂存变更",
                output:
                  "没有暂存的变更。请先用 git add 暂存文件。\n未暂存的变更:\n" +
                  unstaged,
              }
            }
            return { title: "无变更", output: "工作区没有任何变更。" }
          }

          const stat = await $`git diff --cached --stat`.cwd(cwd).text()
          let fullDiff = await $`git diff --cached`.cwd(cwd).text()
          if (fullDiff.length > 8000) {
            fullDiff = fullDiff.substring(0, 8000) + "\n...（diff 已截断）"
          }

          return {
            title: `变更分析 (${staged.trim().split("\n").length} 个文件)`,
            output:
              `## 变更文件\n\`\`\`\n${staged}\n\`\`\`\n\n## 统计\n\`\`\`\n${stat}\n\`\`\`\n\n## 详细 Diff\n\`\`\`diff\n${fullDiff}\n\`\`\`\n\n` +
              "请根据以上变更生成 Conventional Commits 规范的提交信息，然后用 question 工具询问用户是否确认。",
          }
        },
      }),
    },
  }
}

export default { id: "commit-message", server: CommitMessagePlugin }
