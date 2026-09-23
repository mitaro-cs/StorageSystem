import {
	BookOpen,
	CalendarCheck,
	FolderOpen,
	House,
	Newspaper,
	Search,
	Settings,
	UserRound,
	Users
} from '@lucide/svelte';
import { t } from '$lib/i18n/ru';

export const mainNav = [
	{ href: '/', label: t.nav.today, icon: House, key: 'h' },
	{ href: '/news', label: t.nav.news, icon: Newspaper, key: 'n' },
	{ href: '/homework', label: t.nav.homeworkLong, icon: CalendarCheck, key: 'd' },
	{ href: '/materials', label: t.nav.materials, icon: FolderOpen, key: 'm' },
	{ href: '/subjects', label: t.nav.subjects, icon: BookOpen, key: 's' },
	{ href: '/members', label: t.nav.members, icon: Users, key: 'u' },
	{ href: '/settings', label: t.nav.settings, icon: Settings, key: 'o' }
];

export const bottomNav = [
	{ href: '/', label: t.nav.today, icon: House },
	{ href: '/homework', label: t.nav.homework, icon: CalendarCheck },
	{ href: '/materials', label: t.nav.materials, icon: FolderOpen },
	{ href: '/search', label: t.nav.search, icon: Search },
	{ href: '/profile', label: t.nav.profile, icon: UserRound }
];

export function isActive(pathname: string, href: string): boolean {
	return href === '/' ? pathname === '/' : pathname === href || pathname.startsWith(href + '/');
}
