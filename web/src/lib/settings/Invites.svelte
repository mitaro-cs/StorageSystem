<script lang="ts">
	import { Link2, Copy } from '@lucide/svelte';
	import { del, get, post } from '$lib/api';
	import { absolute, copy } from '$lib/copy';
	import { fmtAgo, fmtDue } from '$lib/format';
	import { t } from '$lib/i18n/ru';
	import { fly } from '$lib/motion';
	import { can } from '$lib/session.svelte';
	import { toastError } from '$lib/toasts.svelte';
	import type { GroupRole, Invite } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';

	let { groupId }: { groupId: number } = $props();

	let list = $state<Invite[]>([]);
	let role = $state<GroupRole>('student');
	let uses = $state<'1' | '10' | '50' | 'inf'>('inf');
	let ttl = $state('168');
	let note = $state('');
	let fresh = $state<string | null>(null);
	let busy = $state(false);

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
			<p class="small">Ссылка показывается один раз — скопируйте её сейчас:</p>
			<div class="row">
				<code>{fresh}</code><Button
					size="s"
					onclick={() => fresh && copy(fresh, 'Ссылка скопирована')}
					><Copy size={15} /> Копировать</Button
				>
			</div>
		</div>
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
</style>
