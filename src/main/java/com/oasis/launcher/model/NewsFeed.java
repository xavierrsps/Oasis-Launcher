package com.oasis.launcher.model;

import java.util.List;

/**
 * Represents the news.json feed shown in the launcher UI.
 *
 * <pre>
 * {
 *   "articles": [
 *     {
 *       "title": "Combat rewrite complete",
 *       "date": "2026-05-24",
 *       "summary": "OSRS-accurate melee/range/magic formulas...",
 *       "url": "https://oasis.com/news/combat-rewrite"
 *     }
 *   ]
 * }
 * </pre>
 */
public class NewsFeed {

    public List<Article> articles;

    public static class Article {
        public String title;
        public String date;
        public String summary;
        public String url;
    }
}
