<script lang="ts">
	import { Pencil, Send, Trash2 } from '@lucide/svelte';
	import { del, post, put } from '$lib/api';
	import { session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { GroupChat } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';

	// Закреплённые чаты группы в Telegram: окно открывается с главной, прямо из строки кнопок.
	let { open = $bindable(false), groupId }: { open?: boolean; groupId: number } = $props();

	const chats = $derived(session.me?.groups.find((g) => g.id === groupId)?.chats ?? []);
	let editing = $state<GroupChat | null>(null);
	let title = $state('');
	let url = $state('');
	let error = $state('');
	let busy = $state(false);

	$effect(() => {
		if (open) reset();
	});

	function reset() {
		editing = null;
		title = '';
		url = '';
		error = '';
	}

	function startEdit(c: GroupChat) {
		editing = c;
		title = c.title;
		url = c.url;
		error = '';
	}

	/** Сервер возвращает весь список — обновляем группу в сессии, главная перерисуется сама. */
	function apply(list: GroupChat[]) {
		const g = session.me?.groups.find((x) => x.id === groupId);
		if (g) g.chats = list;
	}

	async function save(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		error = '';
		try {
			const base = `/api/groups/${groupId}/chats`;
			apply(
				editing
					? await put<GroupChat[]>(`${base}/${editing.id}`, { title, url })
					: await post<GroupChat[]>(base, { title, url })
			);
			toast(editing ? 'Чат изменён' : 'Чат закреплён на главной', 'ok');
			reset();
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}

	async function remove(c: GroupChat) {
		if (!window.confirm(`Открепить «${c.title}»?`)) return;
		try {
			apply(await del<GroupChat[]>(`/api/groups/${groupId}/chats/${c.id}`));
			if (editing?.id === c.id) reset();
		} catch (err) {
			toastError(err);
		}
	}
</script>

<Modal bind:open title="Чаты в Telegram">
	<p class="muted small">
		Закреплённые чаты видны всем на главной — одногруппники переходят в них одним нажатием.
	</p>
	{#if chats.length}
		<ul class="list">
			{#each chats as c (c.id)}
				<li>
					<Send size={16} class="tg-icon" />
					<span class="info"
						><strong>{c.title}</strong><span class="faint small"
							>{c.url.replace('https://', '')}</span
						></span
					>
					<button class="icon-btn" onclick={() => startEdit(c)} aria-label="Изменить «{c.title}»"
						><Pencil size={15} /></button
					>
					<button class="icon-btn" onclick={() => remove(c)} aria-label="Открепить «{c.title}»"
						><Trash2 size={15} /></button
					>
				</li>
			{/each}
		</ul>
	{/if}
	<form id="chat-form" class="stack" onsubmit={save}>
		<h3>{editing ? `Изменить «${editing.title}»` : 'Закрепить чат'}</h3>
		<div>
			<label class="label" for="chat-url">Ссылка на чат или канал</label>
			<input
				id="chat-url"
				class="input"
				bind:value={url}
				placeholder="t.me/… или @имя"
				autocomplete="off"
				spellcheck="false"
				required
			/>
			<p class="hint">В Telegram: чат → «Пригласить по ссылке» или имя канала → скопировать.</p>
		</div>
		<div>
			<label class="label" for="chat-title">Название</label>
			<input
				id="chat-title"
				class="input"
				bind:value={title}
				maxlength="40"
				placeholder="Чат группы"
			/>
		</div>
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		{#if editing}<Button onclick={reset}>Отмена</Button>{:else}<Button
				onclick={() => (open = false)}>Готово</Button
			>{/if}
		<Button variant="primary" type="submit" form="chat-form" loading={busy}
			>{editing ? 'Сохранить' : 'Закрепить'}</Button
		>
	{/snippet}
</Modal>

<style>
	.list {
		list-style: none;
		margin: var(--s3) 0;
		padding: 0;
		display: flex;
		flex-direction: column;
	}
	.list li {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 10px 0;
	}
	.list li + li {
		border-top: 1px solid var(--border);
	}
	.list :global(.tg-icon) {
		flex: none;
		color: var(--tg);
	}
	.info {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.info span {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.icon-btn {
		display: grid;
		place-items: center;
		width: 34px;
		height: 34px;
		border: 0;
		border-radius: 50%;
		background: var(--surface-2);
		color: var(--text-2);
	}
	.icon-btn:hover {
		color: var(--text);
	}
	h3 {
		margin-top: var(--s2);
		font-size: 16px;
	}
	.hint {
		margin-top: 4px;
		color: var(--text-3);
		font-size: 13px;
	}
</style>
