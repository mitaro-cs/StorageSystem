<script lang="ts">
	import { get, qs } from '$lib/api';
	import { fmtAgo } from '$lib/format';
	import type { AuditEntry } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';

	let { groupId }: { groupId: number | null } = $props();
	let items = $state<AuditEntry[]>([]);
	let done = $state(false);

	const labels: Record<string, string> = {
		'user.create': 'создал аккаунты',
		'user.block': 'заблокировал пользователя',
		'user.unblock': 'разблокировал пользователя',
		'user.delete': 'удалил аккаунт',
		'user.delete_self': 'удалил свой аккаунт',
		'user.reset_link': 'выдал ссылку сброса пароля',
		'user.activate': 'аккаунт активирован',
		'user.reset': 'пароль сброшен по ссылке',
		'user.password_change': 'сменил пароль',
		'user.instance_role': 'изменил роль инстанса',
		'user.totp_enable': 'включил 2FA',
		'user.totp_disable': 'выключил 2FA',
		'member.role': 'изменил роль участника',
		'member.remove': 'исключил из группы',
		'invite.create': 'создал приглашение',
		'invite.revoke': 'отозвал приглашение',
		'invite.accept': 'регистрация по приглашению',
		'invite.join': 'вступил по приглашению',
		'group.create': 'создал группу',
		'group.update': 'изменил группу',
		'permissions.update': 'изменил права',
		'settings.update': 'изменил настройки',
		'subject.create': 'создал предмет',
		'subject.link': 'сделал предмет общим',
		'subject.link_request': 'запросил общий предмет',
		'subject.link_accept': 'принял общий предмет',
		'news.create': 'опубликовал новость',
		'news.delete': 'удалил новость',
		'news.hide': 'скрыл новость',
		'homework.create': 'опубликовал задание',
		'homework.delete': 'удалил задание',
		'comment.delete': 'удалил комментарий'
	};

	async function load(before?: number) {
		const base = groupId === null ? '/api/admin/audit' : `/api/groups/${groupId}/audit`;
		const page = await get<AuditEntry[]>(base + qs({ before }));
		items = before ? [...items, ...page] : page;
		done = page.length < 50;
	}

	$effect(() => {
		void groupId;
		load();
	});
</script>

<div class="list">
	{#each items as e (e.id)}
		<div class="list-row">
			<div class="info">
				<span
					><strong>{e.actorName || 'Система'}</strong>
					{labels[e.action] ?? e.action}{e.targetId ? ` #${e.targetId}` : ''}</span
				>
				<span class="faint small num">{fmtAgo(e.at)}{e.ip ? ` · ${e.ip}` : ''}</span>
			</div>
		</div>
	{:else}
		<p class="faint empty">Записей нет</p>
	{/each}
</div>
{#if !done && items.length}
	<div class="more">
		<Button variant="ghost" onclick={() => load(items[items.length - 1].id)}>Показать ещё</Button>
	</div>
{/if}

<style>
	.info {
		display: flex;
		flex-direction: column;
	}
	.empty {
		padding: var(--s4);
		text-align: center;
	}
	.more {
		display: flex;
		justify-content: center;
		margin-top: var(--s3);
	}
</style>
