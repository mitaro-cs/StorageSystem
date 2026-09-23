export interface ParsedRow {
	username?: string;
	displayName: string;
}

/**
 * Разбирает вставленный список: столбец имён («Иван Петров» на строку) или CSV
 * (username,display_name). Пустые строки и заголовок CSV пропускаются.
 */
export function parseNames(text: string): ParsedRow[] {
	const out: ParsedRow[] = [];
	for (const raw of text.split(/\r?\n/)) {
		const line = raw.trim();
		if (!line) continue;
		const parts = line.split(/[;,\t]/).map((p) => p.trim().replace(/^"|"$/g, ''));
		if (parts.length >= 2 && /^[a-z0-9][a-z0-9._-]{2,31}$/i.test(parts[0])) {
			if (/^username$/i.test(parts[0])) continue;
			out.push({ username: parts[0].toLowerCase(), displayName: parts.slice(1).join(' ') });
		} else if (!/^(имя|фио|name|display_?name)$/i.test(line)) {
			out.push({ displayName: line.replace(/[;,\t]+/g, ' ').replace(/\s+/g, ' ') });
		}
	}
	return out;
}
