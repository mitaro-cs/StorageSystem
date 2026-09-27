<script lang="ts">
	import { untrack } from 'svelte';
	import { get, post } from '$lib/api';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { Subject } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';

	// «Сделать общим с группой…» со страницы предмета: сразу связать или попросить старосту.
	// Нужно редко — грузится по пункту меню.
	let {
		open = $bindable(),
		subject,
		onsaved
	}: { open: boolean; subject: Subject; onsaved: () => void } = $props();

	let directory = $state<{ id: number; name: string; university: string }[]>([]);
	let shareTo = $state<number | null>(null);

	$effect(() => {
		if (!open) return;
		untrack(async () => {
			shareTo = null;
			try {
				directory = await get('/api/groups/directory');
			} catch (e) {
				toastError(e);
			}
		});
	});

	async function share() {
		if (shareTo === null) return;
		try {
			const r = await post<{ status: string }>(`/api/subjects/${subject.id}/links`, {
				groupId: shareTo
			});
			toast(
				r.status === 'linked' ? 'Предмет стал общим' : 'Запрос отправлен старосте группы',
				'ok'
			);
			open = false;
			onsaved();
		} catch (e) {
			toastError(e);
		}
	}
</script>

<Modal bind:open title="Общий предмет">
	<p class="muted">
		Предмет и его материалы увидит выбранная группа. Если вы не староста этой группы, её староста
		получит запрос.
	</p>
	<div class="options">
		{#each directory.filter((g) => !subject.groups.some((x) => x.id === g.id)) as g (g.id)}
			<label class="check opt"
				><input type="radio" bind:group={shareTo} value={g.id} />
				<span>{g.name} <span class="faint small">{g.university}</span></span></label
			>
		{:else}
			<p class="faint">Других групп на сайте нет</p>
		{/each}
	</div>
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" disabled={shareTo === null} onclick={share}>Связать</Button>
	{/snippet}
</Modal>

<style>
	.options {
		display: flex;
		flex-direction: column;
		gap: 4px;
		margin-top: var(--s3);
	}
	.opt {
		padding: 10px 12px;
		border-radius: var(--r-s);
	}
	.opt:hover {
		background: var(--surface-2);
	}
</style>
