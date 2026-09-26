<script lang="ts">
	import { del, patch, post } from '$lib/api';
	import { ImagePlus, Trash2 } from '@lucide/svelte';
	import SubjectArt from '$lib/ui/SubjectArt.svelte';
	import { toast } from '$lib/toasts.svelte';
	import type { Subject } from '$lib/types';
	import Modal from '$lib/ui/Modal.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import AvatarCropper from '$lib/ui/AvatarCropper.svelte';
	import SubjectGlyph from '$lib/ui/SubjectGlyph.svelte';
	import { ICON_GROUPS, subjectIcon } from '$lib/subjectIcons';
	import { icons } from '$lib/iconLoader.svelte';

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
	let chatUrl = $state('');
	let color = $state(palette[0]);
	/** null — подобрать по названию. */
	let icon = $state<string | null>(null);
	let iconQuery = $state('');
	let pickerOpen = $state(false);
	const current = $derived(subjectIcon(icon, name));
	const groupsShown = $derived(
		ICON_GROUPS.map((g) => ({
			...g,
			icons: g.icons.filter(
				(x) => !iconQuery || x.label.toLowerCase().includes(iconQuery.toLowerCase())
			)
		})).filter((g) => g.icons.length)
	);
	let error = $state('');
	let busy = $state(false);
	let cropper = $state(false);
	/** Фон карточки: широкая картинка вместо иконки. */
	let cover = $state<string | null>(null);
	let coverBusy = $state(false);
	let coverInput: HTMLInputElement | undefined = $state();

	async function uploadCover(e: Event) {
		const input = e.currentTarget as HTMLInputElement;
		const file = input.files?.[0];
		input.value = '';
		if (!file || !edit) return;
		coverBusy = true;
		error = '';
		try {
			const csrf = document.cookie.match(/(?:^|;\s*)(?:__Host-)?gb_csrf=([^;]+)/)?.[1] ?? '';
			const r = await fetch(`/api/subjects/${edit.id}/cover`, {
				method: 'PUT',
				body: file,
				headers: { 'X-CSRF-Token': csrf, 'Content-Type': 'application/octet-stream' }
			});
			const data = await r.json();
			if (!r.ok) throw new Error(data.message ?? 'Не удалось загрузить картинку');
			cover = data.cover;
			onsaved({ ...edit, cover });
			toast('Фон поставлен', 'ok');
		} catch (err) {
			error = err instanceof Error ? err.message : 'Не удалось загрузить картинку';
		} finally {
			coverBusy = false;
		}
	}

	async function removeCover() {
		if (!edit) return;
		coverBusy = true;
		try {
			await del(`/api/subjects/${edit.id}/cover`);
			cover = null;
			onsaved({ ...edit, cover: null });
		} catch (err) {
			error = err instanceof Error ? err.message : 'Ошибка';
		} finally {
			coverBusy = false;
		}
	}

	$effect(() => {
		if (!open) return;
		name = edit?.name ?? '';
		teacher = edit?.teacher ?? '';
		chatUrl = edit?.chatUrl ?? '';
		color = edit?.color ?? palette[Math.floor(Math.random() * 8)];
		icon = edit?.icon ?? null;
		cover = edit?.cover ?? null;
		iconQuery = '';
		pickerOpen = false;
		error = '';
	});

	async function save(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		error = '';
		try {
			const s = edit
				? await patch<Subject>(`/api/subjects/${edit.id}`, {
						name,
						teacher,
						color,
						chatUrl,
						icon: icon ?? ''
					})
				: await post<Subject>(`/api/groups/${groupId}/subjects`, {
						name,
						teacher,
						color,
						chatUrl,
						icon: icon ?? ''
					});
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
		<!-- Фон карточки: картинка вместо иконки — на плитках предмета и в его шапке. -->
		{#if edit}
			<div class="cover-edit">
				<button
					type="button"
					class="cover-pick"
					onclick={() => coverInput?.click()}
					disabled={coverBusy}
					aria-label={cover ? 'Заменить фон карточки' : 'Поставить фон карточки'}
				>
					<SubjectArt id={edit.id} name={name || edit.name} {color} {icon} {cover} class="fill" />
					<span class="cover-hint">
						<ImagePlus size={18} />
						{cover ? 'Заменить фон' : 'Поставить фон вместо иконки'}
					</span>
				</button>
				{#if cover}
					<Button size="s" variant="ghost" onclick={removeCover} disabled={coverBusy}
						><Trash2 size={15} /> Убрать фон</Button
					>
				{/if}
				<input
					bind:this={coverInput}
					class="sr-only"
					type="file"
					accept="image/png,image/jpeg,image/webp"
					tabindex="-1"
					aria-hidden="true"
					onchange={uploadCover}
				/>
			</div>
		{:else}
			<p class="faint small">
				Фон карточки — картинку вместо иконки — можно поставить после создания.
			</p>
		{/if}
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
		<div>
			<label class="label" for="s-chat"
				>Чат предмета в Telegram <span class="faint">(необязательно)</span></label
			>
			<input
				id="s-chat"
				class="input"
				bind:value={chatUrl}
				placeholder="t.me/… или @имя"
				autocomplete="off"
				spellcheck="false"
			/>
		</div>
		<fieldset class="icons">
			<legend class="label">Иконка</legend>
			<div class="current">
				<SubjectGlyph name={name || 'Предмет'} {color} {icon} size={48} />
				<span class="cur-text">
					<strong>{icon ? '' : 'По названию: '}{current.label}</strong>
					<span class="faint small"
						>{icon
							? 'Выбрана вручную'
							: 'Подбирается сама — «Физика» получит атом, «Сети» — схему сети'}</span
					>
				</span>
				<Button size="s" onclick={() => (pickerOpen = !pickerOpen)}
					>{pickerOpen ? 'Свернуть' : 'Выбрать'}</Button
				>
			</div>
			{#if pickerOpen}
				<div class="picker">
					<input
						class="input"
						type="search"
						placeholder="Найти: химия, сети, спорт…"
						bind:value={iconQuery}
						aria-label="Поиск иконки"
					/>
					{#if !iconQuery}
						<button
							type="button"
							class="auto"
							class:on={icon === null}
							aria-pressed={icon === null}
							onclick={() => (icon = null)}
						>
							<SubjectGlyph name={name || 'Предмет'} {color} icon={null} size={30} />
							<span>Подобрать по названию</span>
						</button>
					{/if}
					{#each groupsShown as g (g.title)}
						<p class="group-title">{g.title}</p>
						<div class="grid">
							{#each g.icons as x (x.key)}
								<button
									type="button"
									class="tile"
									class:on={icon === x.key}
									style:--c={color}
									aria-pressed={icon === x.key}
									aria-label={x.label}
									title={x.label}
									onclick={() => (icon = x.key)}
								>
									{#if icons.map?.[x.key]}
										{@const Tile = icons.map[x.key]}
										<Tile size={22} strokeWidth={2} />
									{/if}
								</button>
							{/each}
						</div>
					{:else}
						<p class="faint small">Ничего не нашли — попробуйте другое слово</p>
					{/each}
				</div>
			{/if}
		</fieldset>
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
				<span class="faint small grow">Своя картинка вместо иконки — например, фото учебника</span>
				<Button size="s" onclick={() => (cropper = true)}>Загрузить</Button>
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
	.cover-edit {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 8px;
	}
	.cover-pick {
		position: relative;
		width: 100%;
		aspect-ratio: 16 / 7;
		padding: 0;
		border: 0;
		border-radius: var(--r);
		overflow: hidden;
		background: transparent;
		cursor: pointer;
	}
	.cover-pick :global(.fill) {
		position: absolute;
		inset: 0;
		border-radius: inherit;
	}
	.cover-hint {
		position: absolute;
		left: 10px;
		bottom: 10px;
		display: inline-flex;
		align-items: center;
		gap: 8px;
		padding: 8px 12px;
		border-radius: var(--r-full);
		background: rgb(0 0 0 / 0.6);
		color: #fff;
		font-size: 13.5px;
		font-weight: 600;
		-webkit-backdrop-filter: blur(8px);
		backdrop-filter: blur(8px);
		transition: transform 180ms var(--ease);
	}
	.cover-pick:hover .cover-hint {
		transform: translateY(-2px);
	}
	.icons {
		border: 0;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.current {
		display: flex;
		align-items: center;
		gap: var(--s3);
	}
	.cur-text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		line-height: 1.35;
	}
	.grow {
		flex: 1;
	}
	.picker {
		display: flex;
		flex-direction: column;
		gap: var(--s2);
		padding: var(--s3);
		border-radius: var(--r);
		background: var(--surface-2);
		max-height: 340px;
		overflow-y: auto;
		animation: picker-in 180ms var(--ease);
	}
	@keyframes picker-in {
		from {
			opacity: 0;
			transform: translateY(-4px);
		}
	}
	.group-title {
		margin: var(--s2) 0 0;
		font-size: 12px;
		font-weight: 650;
		letter-spacing: 0.04em;
		text-transform: uppercase;
		color: var(--text-3);
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(44px, 1fr));
		gap: 6px;
	}
	.tile {
		display: grid;
		place-items: center;
		aspect-ratio: 1;
		border: 0;
		border-radius: 12px;
		background: var(--surface);
		color: var(--text-2);
		transition:
			transform 120ms var(--ease),
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.tile:hover {
		transform: translateY(-1px);
		color: var(--c);
	}
	.tile.on {
		color: var(--c);
		background: color-mix(in srgb, var(--c) 16%, var(--surface));
		box-shadow: inset 0 0 0 2px var(--c);
	}
	.auto {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 10px;
		border: 0;
		border-radius: 12px;
		background: var(--surface);
		color: var(--text);
		font: inherit;
		text-align: left;
	}
	.auto.on {
		box-shadow: inset 0 0 0 2px var(--text);
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
