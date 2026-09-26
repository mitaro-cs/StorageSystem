<script lang="ts">
	import { AtSign, Camera } from '@lucide/svelte';
	import { del, patch } from '$lib/api';
	import { t } from '$lib/i18n/ru';
	import { fioError } from '$lib/names';
	import { loadMe, session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import Button from '$lib/ui/Button.svelte';

	// Профиль: фото, ФИО, логин и роли — то, что видят другие.
	const me = $derived(session.me!);
	let displayName = $derived(me.user.displayName);
	let avatarOpen = $state(false);

	async function saveName(e: SubmitEvent) {
		e.preventDefault();
		const problem = fioError(displayName);
		if (problem) return toastError(new Error(problem));
		try {
			await patch('/api/me', { displayName });
			await loadMe();
			toast('ФИО сохранено', 'ok');
		} catch (err) {
			toastError(err);
		}
	}

	async function removeAvatar() {
		try {
			await del('/api/me/avatar');
			await loadMe();
		} catch (err) {
			toastError(err);
		}
	}
</script>

<section class="card pane">
	<div class="who">
		<button
			class="avatar-btn"
			onclick={() => (avatarOpen = true)}
			aria-label="Сменить фото"
			title="Сменить фото"
		>
			<Avatar id={me.user.id} name={me.user.displayName} avatar={me.user.avatar} size={88} ring />
			<span class="cam" aria-hidden="true"><Camera size={16} /></span>
		</button>
		<div class="names">
			<strong>{me.user.displayName}</strong>
			<span class="muted login"><AtSign size={14} />{me.user.username}</span>
			<div class="roles">
				{#if me.user.instanceRole}<span class="chip ink">{t.roles[me.user.instanceRole]}</span>{/if}
				{#each me.groups.filter((g) => g.role) as g (g.id)}<span class="chip"
						>{g.name} · {t.roles[g.role!].toLowerCase()}</span
					>{/each}
			</div>
		</div>
	</div>
	<div class="row wrap">
		<Button onclick={() => (avatarOpen = true)}><Camera size={16} /> Сменить фото</Button>
		{#if me.user.avatar}<Button variant="ghost" onclick={removeAvatar}>Убрать фото</Button>{/if}
	</div>
</section>

<section class="card pane">
	<h3>ФИО</h3>
	<p class="muted small">Как вас видят в группе: в списке участников, у новостей и комментариев.</p>
	<form class="row name-row" onsubmit={saveName}>
		<input
			class="input"
			bind:value={displayName}
			maxlength="64"
			autocomplete="name"
			placeholder="Иванов Иван Иванович"
			aria-label="ФИО"
		/>
		<Button type="submit" disabled={displayName.trim() === me.user.displayName}>Сохранить</Button>
	</form>
	<p class="hint">Логин <strong>@{me.user.username}</strong> не меняется — по нему вы входите.</p>
</section>

{#if avatarOpen}
	{#await import('$lib/ui/AvatarCropper.svelte') then m}
		<m.default
			bind:open={avatarOpen}
			endpoint="/api/me/avatar"
			title="Ваше фото"
			ondone={() => loadMe()}
		/>
	{/await}
{/if}

<style>
	.who {
		display: flex;
		align-items: center;
		gap: var(--s5);
	}
	.avatar-btn {
		position: relative;
		flex: none;
		padding: 0;
		border: 0;
		border-radius: 50%;
		background: none;
		line-height: 0;
	}
	.cam {
		position: absolute;
		right: 0;
		bottom: 0;
		display: grid;
		place-items: center;
		width: 30px;
		height: 30px;
		border-radius: 50%;
		background: var(--accent);
		color: var(--accent-text);
		box-shadow: 0 0 0 3px var(--surface);
	}
	.names {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
	}
	.names strong {
		font-size: 21px;
		letter-spacing: -0.02em;
		line-height: 1.25;
	}
	.login {
		display: inline-flex;
		align-items: center;
		gap: 2px;
	}
	.roles {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
		margin-top: 4px;
	}
	.name-row .input {
		flex: 1;
		min-width: 0;
	}
	.hint strong {
		color: var(--text-2);
	}
	@media (max-width: 480px) {
		.who {
			flex-direction: column;
			text-align: center;
		}
		.names {
			align-items: center;
		}
		.roles {
			justify-content: center;
		}
	}
</style>
