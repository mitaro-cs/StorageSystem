<script lang="ts">
	import { put } from '$lib/api';
	import { loadMe } from '$lib/session.svelte';
	import { toast } from '$lib/toasts.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';
	import { dayToMs, msToDay, type SessionDates } from './session';

	let {
		open = $bindable(),
		groupId,
		dates
	}: { open: boolean; groupId: number; dates: SessionDates | null } = $props();

	let from = $state('');
	let to = $state('');
	let error = $state('');
	let busy = $state(false);

	$effect(() => {
		if (!open) return;
		from = dates ? msToDay(dates.from) : '';
		to = dates ? msToDay(dates.to) : '';
		error = '';
	});

	async function save(body: { from: number | null; to: number | null }, message: string) {
		busy = true;
		error = '';
		try {
			await put(`/api/groups/${groupId}/session`, body);
			await loadMe();
			toast(message, 'ok');
			open = false;
		} catch (e) {
			error = e instanceof Error ? e.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}

	function submit(e: SubmitEvent) {
		e.preventDefault();
		save({ from: dayToMs(from), to: dayToMs(to) }, 'Даты сессии сохранены');
	}
</script>

<Modal bind:open title="Даты сессии">
	<form id="session-form" class="stack" onsubmit={submit}>
		<p class="muted">
			За три недели до начала на главной у всех появится карточка сессии с обратным отсчётом. Зачёты
			и экзамены добавляются как задания с типом «Зачёт» или «Экзамен».
		</p>
		<div class="grid">
			<div>
				<label class="label" for="session-from">Первый день</label>
				<input id="session-from" class="input num" type="date" bind:value={from} required />
			</div>
			<div>
				<label class="label" for="session-to">Последний день</label>
				<input id="session-to" class="input num" type="date" bind:value={to} min={from} required />
			</div>
		</div>
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		{#if dates}
			<Button
				variant="ghost"
				onclick={() => save({ from: null, to: null }, 'Даты сессии убраны')}
				disabled={busy}>Убрать даты</Button
			>
		{/if}
		<span class="spacer"></span>
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" type="submit" form="session-form" loading={busy}>Сохранить</Button>
	{/snippet}
</Modal>

<style>
	.grid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: var(--s3);
	}
	@media (max-width: 420px) {
		.grid {
			grid-template-columns: 1fr;
		}
	}
</style>
