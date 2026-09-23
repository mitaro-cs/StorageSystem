<script lang="ts">
	import { Link2, Copy, Maximize2, Share2 } from '@lucide/svelte';
	import { del, get, post } from '$lib/api';
	import { absolute, canShare, copy, share } from '$lib/copy';
	import { fmtAgo, fmtDue } from '$lib/format';
	import { t } from '$lib/i18n/ru';
	import { fly } from '$lib/motion';
	import { can } from '$lib/session.svelte';
	import { toastError } from '$lib/toasts.svelte';
	import type { GroupRole, Invite } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import QrCode from '$lib/ui/QrCode.svelte';
	import QrScreen from '$lib/ui/QrScreen.svelte';
	import { session } from '$lib/session.svelte';

	let { groupId }: { groupId: number } = $props();

	let list = $state<Invite[]>([]);
	let role = $state<GroupRole>('student');
	let uses = $state<'1' | '10' | '50' | 'inf'>('inf');
	let ttl = $state('168');
	let note = $state('');
	let fresh = $state<string | null>(null);
	let busy = $state(false);
	let fullscreen = $state(false);
	// Ссылка на localhost откроется только на этом компьютере — предупреждаем заранее.
	const local = $derived(
		!session.me?.instance.publicUrl && /^(localhost|127\.|\[?::1\]?$)/.test(location.hostname)
	);
	const groupName = $derived(session.me?.groups.find((g) => g.id === groupId)?.name ?? '');

	async function load() {
		list = await get<Invite[]>(`/api/groups/${groupId}/invites`);
	}
	$effect(() => {
		void groupId;
		fresh = null;
		load();
	});

	async function create(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		try {
			const r = await post<{ path: string }>(`/api/groups/${groupId}/invites`, {
				role,
				maxUses: uses === 'inf' ? null : Number(uses),
				ttlHours: Number(ttl),
				note
			});
			fresh = absolute(r.path);
			note = '';
			load();
		} catch (err) {
			toastError(err);
		} finally {
			busy = false;
		}
	}

	async function revoke(i: Invite) {
		try {
			await del(`/api/groups/${groupId}/invites/${i.id}`);
			load();
		} catch (err) {
			toastError(err);
		}
	}

	const now = Date.now();
	const active = (i: Invite) =>
		!i.revokedAt && i.expiresAt > now && (i.maxUses === null || i.uses < i.maxUses);
</script>

<form class="card form" onsubmit={create}>
	<h2>Новое приглашение</h2>
	<div class="grid">
		<div>
			<label class="label" for="inv-role">Роль</label>
			<select id="inv-role" class="select" bind:value={role}>
				<option value="student">{t.roles.student}</option>
				{#if can('assign_deputy', groupId)}<option value="deputy">{t.roles.deputy}</option>{/if}
				{#if can('assign_headman', groupId)}<option value="headman">{t.roles.headman}</option>{/if}
			</select>
		</div>
		<div>
			<label class="label" for="inv-uses">Сколько раз</label>
			<select id="inv-uses" class="select" bind:value={uses}>
				<option value="1">Одноразовая</option>
				<option value="10">До 10 человек</option>
				<option value="50">До 50 человек</option>
				<option value="inf">Без ограничения</option>
			</select>
		</div>
		<div>
			<label class="label" for="inv-ttl">Действует</label>
			<select id="inv-ttl" class="select" bind:value={ttl}>
				<option value="24">1 день</option>
				<option value="72">3 дня</option>
				<option value="168">Неделю</option>
				<option value="720">30 дней</option>
			</select>
		</div>
	</div>
	<div>
		<label class="label" for="inv-note">Заметка для себя</label>
		<input
			id="inv-note"
			class="input"
			bind:value={note}
			maxlength="100"
			placeholder="Для чата группы"
		/>
	</div>
	<div>
		<Button variant="primary" type="submit" loading={busy}
			><Link2 size={16} /> Создать ссылку</Button
		>
	</div>
	{#if fresh}
		<div class="fresh" in:fly>
			<div class="qr-mini"><QrCode value={fresh} label="QR-код приглашения" /></div>
			<div class="fresh-info">
				<p class="small">
					Ссылка показывается один раз. Отправьте её в чат группы или покажите QR-код на паре —
					одногруппники отсканируют его камерой.
				</p>
				<code class="link">{fresh}</code>
				{#if local}
					<p class="tip amber small">
						<span
							>Доступ для группы ещё не открыт — с других телефонов по этой ссылке не зайти.
							Откройте его в <a href="/settings?tab=server">Настройки → Сервер</a>, и ссылки поведут
							на адрес, который видят одногруппники.</span
						>
					</p>
				{/if}
				<div class="row wrap">
					<Button size="s" variant="primary" onclick={() => (fullscreen = true)}
						><Maximize2 size={15} /> QR на весь экран</Button
					>
					<Button size="s" onclick={() => fresh && copy(fresh, 'Ссылка скопирована')}
						><Copy size={15} /> Копировать</Button
					>
					{#if canShare()}<Button
							size="s"
							onclick={() => fresh && share(fresh, `Вступайте в ${groupName}`)}
							><Share2 size={15} /> Поделиться</Button
						>{/if}
				</div>
			</div>
		</div>
		<QrScreen bind:open={fullscreen} value={fresh} title="Вступайте в {groupName}" />
	{/if}
</form>

<h2 class="sub">Выданные ссылки</h2>
<div class="list">
	{#each list as i (i.id)}
		<div class="list-row" class:dead={!active(i)}>
			<div class="info">
				<strong>{t.roles[i.role]}{i.note ? ` · ${i.note}` : ''}</strong>
				<span class="faint small num">
					использовано {i.uses}{i.maxUses !== null ? ` из ${i.maxUses}` : ''} ·
					{i.revokedAt ? 'отозвана' : i.expiresAt < now ? 'истекла' : `до ${fmtDue(i.expiresAt)}`} · создана
					{fmtAgo(i.createdAt)}
				</span>
			</div>
			{#if active(i)}<Button size="s" variant="ghost" onclick={() => revoke(i)}>Отозвать</Button
				>{/if}
		</div>
	{:else}
		<p class="faint empty">Приглашений ещё не было</p>
	{/each}
</div>

<style>
	.form {
		display: flex;
		flex-direction: column;
		gap: var(--s4);
		padding: var(--s5);
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		gap: var(--s3);
	}
	@media (max-width: 600px) {
		.grid {
			grid-template-columns: 1fr;
		}
	}
	.fresh {
		padding: 12px;
		border-radius: var(--r);
		background: var(--accent-soft);
	}
	.fresh code {
		flex: 1;
		min-width: 0;
		word-break: break-all;
		font-size: 13px;
	}
	.sub {
		font-size: 17px;
		margin: var(--s5) 0 var(--s3);
	}
	.info {
		flex: 1;
		display: flex;
		flex-direction: column;
	}
	.dead {
		opacity: 0.55;
	}
	.empty {
		padding: var(--s4);
		text-align: center;
	}
	.fresh {
		display: flex;
		gap: var(--s4);
		align-items: flex-start;
		flex-wrap: wrap;
	}
	.qr-mini {
		width: 132px;
		flex: none;
	}
	.fresh-info {
		flex: 1;
		min-width: 220px;
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.fresh-info .link {
		word-break: break-all;
		font-size: 12.5px;
	}
</style>
