FROM eclipse-temurin:21-jdk

ARG TZ
ARG CLAUDE_CODE_VERSION=latest
ARG USERNAME=developer

ENV TZ="$TZ"
ENV DEVCONTAINER=true

# System dependencies: git, firewall tooling, zsh, Node.js prerequisites
RUN apt-get update && apt-get install -y --no-install-recommends \
  git \
  sudo \
  zsh \
  curl \
  gnupg2 \
  iptables \
  ipset \
  iproute2 \
  dnsutils \
  aggregate \
  jq \
  neovim \
  && apt-get clean && rm -rf /var/lib/apt/lists/*

# GitHub CLI
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
      | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg \
  && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
      | tee /etc/apt/sources.list.d/github-cli.list > /dev/null \
  && apt-get update && apt-get install -y gh \
  && apt-get clean && rm -rf /var/lib/apt/lists/*

# Node.js (required by Claude Code)
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
  && apt-get install -y nodejs \
  && apt-get clean && rm -rf /var/lib/apt/lists/*

# Claude Code
RUN npm install -g @anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}

# Repurpose the existing 'ubuntu' user (UID/GID 1000) from the base image
RUN usermod -l $USERNAME -d /home/$USERNAME -m -s /bin/zsh ubuntu \
  && groupmod -n $USERNAME ubuntu \
  && echo "$USERNAME ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/$USERNAME \
  && chmod 0440 /etc/sudoers.d/$USERNAME

# Firewall script
COPY init-firewall.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/init-firewall.sh

# Workspace
RUN mkdir -p /workspace && chown $USERNAME:$USERNAME /workspace
WORKDIR /workspace

USER $USERNAME
ENV SHELL=/bin/zsh

# Minimal zshrc
RUN echo 'autoload -Uz compinit && compinit' > ~/.zshrc \
  && echo 'PROMPT="%F{blue}%~%f %# "' >> ~/.zshrc \
  && echo 'alias ll="ls -la"' >> ~/.zshrc
