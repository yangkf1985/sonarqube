/*
 * SonarQube
 * Copyright (C) 2009-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
import { shallow } from 'enzyme';
import * as React from 'react';
import {
  mockAzureBindingDefinition,
  mockGithubBindingDefinition
} from '../../../../../helpers/mocks/alm-settings';
import {
  AlmBindingDefinition,
  AlmKeys,
  AzureBindingDefinition
} from '../../../../../types/alm-settings';
import AlmTabRenderer, { AlmTabRendererProps } from '../AlmTabRenderer';

it('should render correctly for multi-ALM binding', () => {
  expect(shallowRenderAzure({ loadingAlmDefinitions: true })).toMatchSnapshot(
    'loading ALM definitions'
  );
  expect(shallowRenderAzure({ loadingProjectCount: true })).toMatchSnapshot(
    'loading project count'
  );
  expect(shallowRenderAzure({ submitting: true })).toMatchSnapshot('submitting');
  expect(shallowRenderAzure()).toMatchSnapshot('loaded');
  expect(shallowRenderAzure({ editedDefinition: mockAzureBindingDefinition() })).toMatchSnapshot(
    'editing a definition'
  );
  expect(
    shallowRenderAzure({
      features: [
        {
          active: true,
          name: 'Foo',
          description: 'Bar'
        },
        {
          active: false,
          name: 'Baz',
          description: 'Bim'
        }
      ]
    })
  ).toMatchSnapshot('with features');
});

it('should render correctly for single-ALM binding', () => {
  expect(
    shallowRenderAzure({ loadingAlmDefinitions: true, multipleAlmEnabled: false })
  ).toMatchSnapshot();
  expect(shallowRenderAzure({ multipleAlmEnabled: false })).toMatchSnapshot();
  expect(
    shallowRenderAzure({ definitions: [mockAzureBindingDefinition()], multipleAlmEnabled: false })
  ).toMatchSnapshot();
});

it('should render correctly with validation', () => {
  const githubProps = {
    alm: AlmKeys.GitHub,
    defaultBinding: mockGithubBindingDefinition(),
    definitions: [mockGithubBindingDefinition()]
  };
  expect(shallowRender(githubProps)).toMatchSnapshot();
  expect(shallowRender({ ...githubProps, definitions: [] })).toMatchSnapshot('empty');

  expect(
    shallowRender({
      ...githubProps,
      editedDefinition: mockGithubBindingDefinition()
    })
  ).toMatchSnapshot('create a second');

  expect(
    shallowRender({
      ...githubProps,
      definitions: [],
      editedDefinition: mockGithubBindingDefinition()
    })
  ).toMatchSnapshot('create a first');
});

function shallowRenderAzure(props: Partial<AlmTabRendererProps<AzureBindingDefinition>> = {}) {
  return shallowRender({
    defaultBinding: mockAzureBindingDefinition(),
    definitions: [mockAzureBindingDefinition()],
    ...props
  });
}

function shallowRender<B extends AlmBindingDefinition>(
  props: Partial<AlmTabRendererProps<B>> = {}
) {
  return shallow(
    <AlmTabRenderer
      additionalColumnsHeaders={[]}
      additionalColumnsKeys={[]}
      alm={AlmKeys.Azure}
      defaultBinding={{} as any}
      definitions={[]}
      definitionStatus={{}}
      form={jest.fn()}
      loadingAlmDefinitions={false}
      loadingProjectCount={false}
      multipleAlmEnabled={true}
      onCancel={jest.fn()}
      onCheck={jest.fn()}
      onCreate={jest.fn()}
      onDelete={jest.fn()}
      onEdit={jest.fn()}
      onSubmit={jest.fn()}
      submitting={true}
      success={false}
      {...props}
    />
  );
}
