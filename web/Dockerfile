# Static build served by nginx. Build from web/ with the tags from deploy/images.env (deploy/k3d/up.sh does this).
ARG NODE_IMAGE
ARG NGINX_IMAGE

FROM ${NODE_IMAGE} AS build
WORKDIR /web
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM ${NGINX_IMAGE}
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist /usr/share/nginx/html
