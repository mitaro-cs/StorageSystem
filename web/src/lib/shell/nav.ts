import {
	Bell,
	BookOpen,
	CalendarCheck,
	FolderOpen,
	GraduationCap,
	House,
	Newspaper,
	ShieldCheck,
	SlidersHorizontal,
	UserRound,
	Users
} from '@lucide/svelte';
import { t } from '$lib/i18n/ru';

export const mainNav = [
	{ href: '/', label: t.nav.today, icon: House, key: 'h' },
	{ href: '/news', label: t.nav.news, icon: Newspaper, key: 'n' },
	{ href: '/homework', label: t.nav.homeworkLong, icon: CalendarCheck, key: 'd' },
	{ href: '/session', label: t.nav.session, icon: GraduationCap, key: 'e' },
	{ href: '/materials', label: t.nav.materials, icon: FolderOpen, key: 'm' },
	{ href: '/subjects', label: t.nav.subjects, icon: BookOpen, key: 's' },
	{ href: '/members', label: t.nav.members, icon: Users, key: 'u' },
	{ href: '/moderation', label: t.nav.moderation, icon: ShieldCheck, key: 'r' },
	{ href: '/notifications', label: t.nav.notifications, icon: Bell, key: 'b' },
	{ href: '/settings', label: t.nav.settings, icon: SlidersHorizontal, key: 'o' }
];

/**
 * Нижняя панель телефона — те же главные разделы, что в боковой панели компьютера. Файлы — внутри
 * предметов, поиск — в верхней строке, участники и настройки — в профиле.
 */
export const bottomNav = [
	{ href: '/', label: t.nav.today, icon: House },
	{ href: '/news', label: t.nav.news, icon: Newspaper },
	{ href: '/homework', label: t.nav.homework, icon: CalendarCheck },
	{ href: '/subjects', label: t.nav.subjects, icon: BookOpen },
	{ href: '/profile', label: t.nav.profile, icon: UserRound }
];

export function isActive(pathname: string, href: string): boolean {
	return href === '/' ? pathname === '/' : pathname === href || pathname.startsWith(href + '/');
}
