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

/** Сайт на нескольких компьютерах хоста (GET /api/host). */
export type HostRole = 'off' | 'host' | 'checking' | 'standby' | 'waiting' | 'switching';

export interface Hosts {
	/** Приложение хоста: здесь это возможно. */
	available: boolean;
	/** Этот компьютер подключён к общей папке. */
	enabled: boolean;
	role: HostRole;
	/** Почему ожидание: live — работает на другом, silent — тот не на связи, и т. п. */
	plan: 'host' | 'take' | 'live' | 'silent' | 'handed' | 'detached' | null;
	message: string | null;
	error: string | null;
	computer: { id: string; name: string } | null;
	/** Хост, если это другой компьютер. */
	other: { name: string; state: string; heartbeat: number; fresh: boolean } | null;
	snapshotAt: number | null;
	snapshotBy: string | null;
	/** Что предложить: request — попросить передать, start — запустить здесь, back — вернуть. */
	action: 'request' | 'start' | 'back' | null;
	folder: string | null;
	cloud: string | null;
	choices: { label: string; path: string }[];
	/** Докачка облаком: сколько файлов уже есть из скольких. */
	have: number | null;
	total: number | null;
}

/** Сайт, найденный в облачной папке при первом запуске. */
export interface FoundSite {
	path: string;
	cloud: string;
	name: string;
	host: string | null;
	at: number | null;
}
