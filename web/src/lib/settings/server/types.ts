export type AccessState = 'off' | 'starting' | 'online' | 'retrying' | 'error' | 'needs_login';
export type AccessMode = 'off' | 'fxtunnel' | 'cloudpub' | 'lan' | 'manual';

export interface Access {
	mode: AccessMode;
	state: AccessState;
	message: string | null;
	url: string | null;
	fixed: boolean;
	desktop: boolean;
	fxtunnel: {
		loggedIn: boolean;
		login: { url: string; code: string; expiresAt: number } | null;
		subdomain: string | null;
		suggested: string;
		domain: string;
	};
	cloudpub: { loggedIn: boolean; url: string | null };
	lan: { available: boolean; urls: string[] };
	manualUrl: string | null;
}

export interface BackupInfo {
	name: string;
	size: number;
	createdAt: number;
}

export interface Backups {
	enabled: boolean;
	keep: number;
	dir: string;
	cloud: { label: string; path: string } | null;
	choices: { label: string; path: string }[];
	status: { lastOkAt: number | null; lastError: string | null; lastErrorAt: number | null };
	items: BackupInfo[];
}

export interface Status {
	version: string;
	desktop: boolean;
	startedAt: number;
	dataDir: string | null;
	sizes: { database: number; files: number; free: number };
	backup: Backups['status'];
	update: { version: string; url: string } | null;
	/** Окно приложения хоста: обновление ставится кнопкой, без скачивания вручную. */
	canUpdate: boolean;
	/** Последняя проверка обновлений (у серверов до 0.4.7 её нет). */
	check?: UpdateCheck;
}

export interface UpdateCheck {
	/** Последний выпуск; null — ещё не узнали. */
	latest: string | null;
	/** Когда проверяли, 0 — ни разу. */
	checkedAt: number;
	/** Почему не удалось проверить; null — удалось. */
	error: string | null;
}
