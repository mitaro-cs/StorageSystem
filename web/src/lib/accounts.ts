/**
 * Аккаунты, которые входили на этом устройстве: на странице входа их выбирают касанием, а не
 * вводят логин. Хранится только в браузере; «Чужое устройство» при входе — не запоминать.
 */
export interface KnownAccount {
	userId: number;
	username: string;
	displayName: string;
	avatar: string | null;
}

const KEY = 'gb-accounts';
const REMEMBER = 'gb-remember';

export function knownAccounts(): KnownAccount[] {
	try {
		return JSON.parse(localStorage.getItem(KEY) ?? '[]');
	} catch {
		return [];
	}
}

function save(list: KnownAccount[]) {
	try {
		localStorage.setItem(KEY, JSON.stringify(list.slice(0, 5)));
	} catch {
		/* не запоминаем */
	}
}

export function rememberAccount(a: KnownAccount) {
	if (!rememberDevice()) return;
	save([a, ...knownAccounts().filter((x) => x.userId !== a.userId)]);
}

export function forgetAccount(userId: number) {
	save(knownAccounts().filter((x) => x.userId !== userId));
}

export function rememberDevice(): boolean {
	try {
		return localStorage.getItem(REMEMBER) !== 'no';
	} catch {
		return false;
	}
}

export function setRememberDevice(v: boolean) {
	try {
		localStorage.setItem(REMEMBER, v ? 'yes' : 'no');
		if (!v) localStorage.removeItem(KEY);
	} catch {
		/* не запоминаем */
	}
}
