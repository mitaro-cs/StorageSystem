<script lang="ts">
	import { patch, post } from '$lib/api';
	import { loadMe, session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import type { MeGroup } from '$lib/types';
	import Button from '$lib/ui/Button.svelte';

	let { group }: { group: MeGroup } = $props();

	let name = $state('');
	let university = $state('');
	let course = $state<number | null>(null);
	let busy = $state(false);
	let newName = $state('');

	$effect(() => {
		name = group.name;
		university = group.university;
		course = group.course;
	});

	async function save(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		try {
			await patch(`/api/groups/${group.id}`, { name, university, course });
			await loadMe();
			toast('Группа сохранена', 'ok');
		} catch (err) {
			toastError(err);
		} finally {
			busy = false;
		}
	}

	async function create(e: SubmitEvent) {
		e.preventDefault();
		try {
			await post('/api/groups', { name: newName, university });
			newName = '';
			await loadMe();
			toast('Группа создана', 'ok');
		} catch (err) {
			toastError(err);
		}
	}
</script>

<form class="card form" onsubmit={save}>
	<h2>Группа</h2>
	<div class="grid">
		<div>
			<label class="label" for="g-name">Название</label>
			<input id="g-name" class="input" bind:value={name} maxlength="40" required />
		</div>
		<div>
			<label class="label" for="g-course">Курс</label>
			<input id="g-course" class="input num" type="number" min="1" max="6" bind:value={course} />
		</div>
	</div>
	<div>
		<label class="label" for="g-uni">Вуз</label>
		<input id="g-uni" class="input" bind:value={university} maxlength="80" />
	</div>
	<div><Button variant="primary" type="submit" loading={busy}>Сохранить</Button></div>
</form>

{#if session.me?.instance.mode === 'multi'}
	<form class="card form" onsubmit={create}>
		<h2>Новая группа</h2>
		<div class="row">
			<input
				class="input"
				bind:value={newName}
				placeholder="БИН2510"
				maxlength="40"
				required
				aria-label="Название новой группы"
			/>
			<Button type="submit">Создать</Button>
		</div>
	</form>
{/if}

<style>
	.form {
		display: flex;
		flex-direction: column;
		gap: var(--s4);
		padding: var(--s5);
		margin-bottom: var(--s4);
	}
	.grid {
		display: grid;
		grid-template-columns: 1fr 96px;
		gap: var(--s3);
	}
</style>
