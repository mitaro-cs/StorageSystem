import type { AuditEntry } from '$lib/types';

/**
 * Действия журнала по-русски: «опубликовал новость», а не «news.create». Ключи — то, что пишет
 * сервер (audit.log). Новое действие без подписи показывается общей фразой по его разделу.
 */
const LABELS: Record<string, string> = {
	'access.login': 'подключил сервис доступа для группы',
	'access.logout': 'отключил сервис доступа',
	'access.mode': 'сменил доступ для группы',
	'appearance.update': 'сменил фон страницы входа',
	'backup.create': 'сделал резервную копию',
	'backup.restore': 'восстановил сайт из копии',
	'backup.settings': 'изменил настройки копий',
	'comment.delete': 'удалил комментарий',
	'comment.hide': 'скрыл комментарий',
	'comment.unhide': 'вернул комментарий',
	'export.group': 'скачал архив группы',
	'export.user': 'скачал свои данные',
	'folder.delete': 'удалил папку',
	'group.archive': 'отправил группу в архив',
	'group.avatar': 'сменил картинку группы',
	'group.chat_add': 'закрепил чат в Telegram',
	'group.chat_remove': 'открепил чат',
	'group.chat_update': 'изменил чат',
	'group.create': 'создал группу',
	'group.session': 'указал даты сессии',
	'group.session_nav': 'настроил раздел «Сессия»',
	'group.unarchive': 'вернул группу из архива',
	'group.update': 'изменил группу',
	'homework.create': 'опубликовал задание',
	'homework.delete': 'удалил задание',
	'homework.hide': 'скрыл задание',
	'homework.unhide': 'вернул задание',
	'hosts.disable': 'выключил работу на нескольких компьютерах',
	'hosts.enable': 'включил работу на нескольких компьютерах',
	'hosts.transfer_code': 'выдал код переноса сайта',
	'invite.accept': 'зарегистрировался по приглашению',
	'invite.create': 'создал приглашение',
	'invite.join': 'вступил по приглашению',
	'invite.revoke': 'отозвал приглашение',
	'material.approve': 'одобрил материал',
	'material.create': 'добавил материал',
	'material.delete': 'удалил материал',
	'material.hide': 'скрыл материал',
	'material.reject': 'отклонил материал',
	'material.suggest': 'предложил материал',
	'material.unhide': 'вернул материал',
	'member.remove': 'исключил из группы',
	'member.role': 'изменил роль участника',
	'news.create': 'опубликовал новость',
	'news.delete': 'удалил новость',
	'news.hide': 'скрыл новость',
	'news.unhide': 'вернул новость',
	'permissions.update': 'изменил права',
	'report.dismiss': 'отклонил жалобу',
	'settings.update': 'изменил настройки сайта',
	'subject.archive': 'отправил предмет в архив',
	'subject.create': 'создал предмет',
	'subject.link': 'сделал предмет общим',
	'subject.link_accept': 'принял общий предмет',
	'subject.link_reject': 'отклонил общий предмет',
	'subject.link_request': 'попросил сделать предмет общим',
	'subject.unarchive': 'вернул предмет из архива',
	'subject.unlink': 'убрал предмет из группы',
	'user.activate': 'вошёл впервые по ссылке',
	'user.block': 'заблокировал пользователя',
	'user.create': 'создал аккаунты',
	'user.delete': 'удалил аккаунт',
	'user.delete_self': 'удалил свой аккаунт',
	'user.instance_role': 'изменил роль на сайте',
	'user.passkey_add': 'добавил ключ входа',
	'user.passkey_remove': 'удалил ключ входа',
	'user.password_change': 'сменил пароль',
	'user.qr_approve': 'вошёл на другом устройстве по QR-коду',
	'user.recovery_code_used': 'вошёл по резервному коду',
	'user.recovery_codes': 'получил новые резервные коды',
	'user.reset': 'сменил пароль по ссылке',
	'user.reset_link': 'выдал ссылку сброса пароля',
	'user.totp_disable': 'выключил двухфакторную защиту',
	'user.totp_enable': 'включил двухфакторную защиту',
	'user.unblock': 'разблокировал пользователя'
};

/** Если подписи нет — хотя бы раздел: «изменил группу», а не «group.something». */
const SECTIONS: Record<string, string> = {
	access: 'изменил доступ для группы',
	backup: 'работал с резервными копиями',
	comment: 'изменил комментарий',
	group: 'изменил группу',
	homework: 'изменил задание',
	hosts: 'изменил компьютеры хоста',
	invite: 'изменил приглашение',
	material: 'изменил материал',
	member: 'изменил участника',
	news: 'изменил новость',
	subject: 'изменил предмет',
	user: 'изменил аккаунт'
};

export function auditLabel(action: string): string {
	return LABELS[action] ?? SECTIONS[action.split('.')[0]] ?? 'изменил настройки';
}

function details(e: AuditEntry): Record<string, unknown> {
	if (!e.details) return {};
	try {
		const v = JSON.parse(e.details);
		return v && typeof v === 'object' ? v : {};
	} catch {
		return {};
	}
}

/** Название записи из подробностей: заголовок новости, имя предмета, чат. */
export function auditTarget(e: AuditEntry): string | null {
	const d = details(e);
	const v = d.title ?? d.name ?? d.text;
	return typeof v === 'string' && v.trim() ? v.trim() : null;
}

/** Куда ведёт запись, если она ещё есть: новость, задание, материал, предмет. */
export function auditLink(e: AuditEntry): string | null {
	if (e.targetId === null || /\.delete/.test(e.action)) return null;
	switch (e.targetType) {
		case 'post':
			return `/news/${e.targetId}`;
		case 'homework':
			return `/homework/${e.targetId}`;
		case 'material':
			return `/materials/${e.targetId}`;
		case 'subject':
			return `/subjects/${e.targetId}`;
		default:
			return null;
	}
}
