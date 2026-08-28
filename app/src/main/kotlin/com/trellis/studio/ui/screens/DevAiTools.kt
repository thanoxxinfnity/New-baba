package com.trellis.studio.ui.screens

/** AI tools aimed at developers — reuse the AiToolScreen engine, grouped
 *  separately from the casual AI tools. */
val DEV_AI_TOOLS: List<AiToolSpec> = listOf(
    AiToolSpec("dev_regex", "Regex Builder", "Describe it → get the regex",
        "e.g. match an email address",
        "You are a regex expert. For the user's request, output the regex on its own line, then a short " +
            "explanation and one example match. Keep it correct and portable.", emptyList(), 0.2),
    AiToolSpec("dev_sql", "SQL Builder", "Plain English → SQL",
        "e.g. top 5 customers by total orders",
        "You are a SQL expert. Turn the user's request into a correct SQL query (assume sensible table/column " +
            "names), output the query in a code block, then one line explaining it.", emptyList(), 0.2),
    AiToolSpec("dev_review", "Code Reviewer", "Find bugs & smells",
        "Paste code to review…",
        "You are a senior engineer. Review the user's code: list concrete bugs, risks and improvements as short " +
            "bullet points, most important first. Be specific.", emptyList(), 0.3),
    AiToolSpec("dev_commit", "Commit Message", "Diff → clean message",
        "Paste your diff or describe the change…",
        "Write a Conventional Commits message (type(scope): summary) for the change, plus a short body of 1-3 " +
            "bullet points. Output only the message.", emptyList(), 0.3),
    AiToolSpec("dev_error", "Error Explainer", "Stack trace → fix",
        "Paste the error / stack trace…",
        "You are a debugging expert. Explain what the user's error means, the likely cause, and the exact fix " +
            "with a code snippet if useful. Be concise.", emptyList(), 0.3),
    AiToolSpec("dev_convert", "Code Converter", "Port between languages",
        "Paste code to convert…",
        "Convert the user's code to {opt}. Output the converted code in a code block, keeping the logic. Add a " +
            "one-line note only if something can't map directly.",
        listOf("Python", "JavaScript", "TypeScript", "Kotlin", "Java", "Go", "Rust", "C++", "Swift"), 0.2),
    AiToolSpec("dev_cron", "Cron Builder", "Schedule → cron",
        "e.g. every weekday at 9:30am",
        "Turn the user's schedule into a 5-field cron expression. Output the cron on its own line, then a one-line " +
            "human explanation.", emptyList(), 0.2),
    AiToolSpec("dev_doc", "Doc Generator", "Code → docs/comments",
        "Paste a function or class…",
        "Add clear doc comments to the user's code (docstring/KDoc/JSDoc as fits the language) explaining params " +
            "and return. Output the fully documented code.", emptyList(), 0.3),
    AiToolSpec("dev_tests", "Test Generator", "Code → unit tests",
        "Paste a function to test…",
        "Write thorough unit tests for the user's code (pick the idiomatic framework for the language), covering " +
            "normal, edge and error cases. Output the test code.", emptyList(), 0.3),
    AiToolSpec("dev_config", "Config Generator", ".gitignore, Dockerfile…",
        "Describe your project (e.g. Node + Postgres)…",
        "Generate a production-ready {opt} for the user's project. Output only the file contents in a code block.",
        listOf(".gitignore", "Dockerfile", "docker-compose.yml", "GitHub Action", "nginx.conf", "Makefile"), 0.3),
)
