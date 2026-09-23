<script lang="ts">
	import { patch, post } from '$lib/api';
	import { toast } from '$lib/toasts.svelte';
	import type { Subject } from '$lib/types';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import AvatarCropper from '$lib/ui/AvatarCropper.svelte';

	interface Props {
		open: boolean;
		groupId?: number | null;
		edit?: Subject | null;
		onsaved: (s: Subject) => void;
	}

	let { open = $bindable(), groupId = null, edit = null, onsaved }: Props = $props();

	const palette = [
		'#3446d4',
		'#0e9f6e',
		'#d97706',
		'#dc2626',
		'#7c3aed',
		'#0891b2',
		'#db2777',
		'#65a30d',
		'#6b7280'
	];

	let name = $state('');
	let teacher = $state('');
	let color = $state(palette[0]);
	let error = $state('');
	let busy = $state(false);
	let cropper = $state(false);

	$effect(() => {
		if (!open) return;
		name = edit?.name ?? '';
		teacher = edit?.teacher ?? '';
		color = edit?.color ?? palette[Math.floor(Math.random() * 8)];
		error = '';
	});

	async function save(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		error = '';
		try {
			const s = edit
				? await patch<Subject>(`/api/subjects/${edit.id}`, { name, teacher, color })
				: await post<Subject>(`/api/groups/${groupId}/subjects`, { name, teacher, color });
			toast(edit ? 'Предмет обновлён' : 'Предмет создан', 'ok');
			open = false;
			onsaved(s);
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			busy = false;
		}
	}
</script>

<Modal bind:open title={edit ? 'Предмет' : 'Новый предмет'}>
	<form id="subject-form" class="stack form" onsubmit={save}>
		<div>
			<label class="label" for="s-name">Название</label>
			<input
				id="s-name"
				class="input"
				bind:value={name}
				maxlength="80"
				required
				placeholder="Математический анализ"
			/>
		</div>
		<div>
			<label class="label" for="s-teacher"
				>Преподаватель <span class="faint">(необязательно)</span></label
			>
			<input id="s-teacher" class="input" bind:value={teacher} maxlength="80" />
		</div>
		{#if edit}
			<div class="row">
				<Avatar
					id={edit.id}
					name={edit.name}
					avatar={edit.avatar}
					size={40}
					kind="subject"
					square
				/>
				<Button size="s" onclick={() => (cropper = true)}>Иконка предмета</Button>
			</div>
		{/if}
		<fieldset class="colors">
			<legend class="label">Цвет метки</legend>
			{#each palette as c (c)}
				<button
					type="button"
					class="sw"
					class:on={color === c}
					style:background={c}
					aria-label="Цвет {c}"
					aria-pressed={color === c}
					onclick={() => (color = c)}
				></button>
			{/each}
			<input type="color" bind:value={color} aria-label="Свой цвет" />
		</fieldset>
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" type="submit" form="subject-form" loading={busy}
			>{edit ? 'Сохранить' : 'Создать'}</Button
		>
	{/snippet}
</Modal>

{#if edit}
	<AvatarCropper
		bind:open={cropper}
		endpoint="/api/subjects/{edit.id}/avatar"
		title="Иконка предмета"
		ondone={(a) => edit && onsaved({ ...edit, avatar: a })}
	/>
{/if}

<style>
	.form {
		gap: var(--s4);
	}
	.colors {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px;
	}
	.colors legend {
		width: 100%;
	}
	.sw {
		width: 28px;
		height: 28px;
		border: 0;
		border-radius: 9px;
		box-shadow: inset 0 0 0 1px rgb(0 0 0 / 0.08);
		transition: transform 120ms var(--ease);
	}
	.sw.on {
		outline: 2px solid var(--text);
		outline-offset: 2px;
	}
	.sw:hover {
		transform: scale(1.08);
	}
	input[type='color'] {
		width: 36px;
		height: 30px;
		padding: 0;
		border: 0;
		background: none;
	}
</style>
